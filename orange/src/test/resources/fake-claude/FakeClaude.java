import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for {@code claude -p --output-format stream-json} used by orange's tests.
 * Launched as a single-file source program (JDK only, no package). It reads the prompt from
 * stdin, picks a scenario from a {@code [[fake:<name>]]} marker in the prompt or in the
 * {@code --append-system-prompt} text, and prints realistic stream-json lines.
 *
 * <p>In every scenario it records its arguments and environment as {@code fake-args.json} and
 * {@code fake-env.json} under {@code $CLAUDE_CONFIG_DIR}, so tests can assert what the adapter
 * passed in.
 */
public class FakeClaude {

    private static final Pattern MARKER = Pattern.compile("\\[\\[fake:([a-z-]+)]]");

    public static void main(String[] args) throws Exception {
        String model = "claude-sonnet-5-5";
        String system = "";
        String session = "fake-session";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--model" -> model = args[++i];
                case "--append-system-prompt" -> system = args[++i];
                case "--session-id" -> session = args[++i];
                default -> { }
            }
        }
        String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);

        Path configDir = Path.of(System.getenv().getOrDefault("CLAUDE_CONFIG_DIR", "."));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("fake-args.json"), jsonArray(List.of(args)));
        Files.writeString(configDir.resolve("fake-env.json"), jsonObject(System.getenv()));

        Matcher m = MARKER.matcher(prompt + "\n" + system);
        String scenario = m.find() ? m.group(1) : "success";
        Path cwd = Path.of("").toAbsolutePath();

        emit("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":" + q(session) + ",\"model\":" + q(model)
                + ",\"cwd\":" + q(cwd.toString()) + ",\"tools\":[\"Read\",\"Edit\",\"Bash\"]}");

        switch (scenario) {
            case "error" -> {
                assistant("msg_1", model, "Something broke", 100, 20);
                result(true, "error_during_execution", "boom", null, session);
                System.exit(1);
            }
            case "plan-limit" -> {
                result(true, "error_during_execution",
                        "Claude usage limit reached. Your limit will reset at 5pm (Europe/Berlin).", null, session);
                System.exit(1);
            }
            case "hang" -> {
                Process child = new ProcessBuilder("sleep", "600").start();
                Files.writeString(configDir.resolve("child.pid"), Long.toString(child.pid()));
                assistant("msg_1", model, "Running a long command", 100, 20);
                sleepForever();
            }
            case "expensive" -> {
                assistant("msg_1", model, "Reading the whole repository", 5_000_000, 100);
                sleepForever();
            }
            case "tester-pass" -> {
                Files.writeString(cwd.resolve("report.md"), "VERDICT: PASS\n- ran the test suite: all green\n");
                finishOk(model, session, "Tests pass.");
            }
            case "tester-fail" -> {
                Files.writeString(cwd.resolve("report.md"), "VERDICT: FAIL\n- SnakeTest.moves failed: expected 2 but was 1\n");
                finishOk(model, session, "Tests fail.");
            }
            case "review-fail" -> finishOk(model, session, "Found a missing null check in Board.java.\nVERDICT: FAIL");
            case "plan" -> {
                Files.writeString(cwd.resolve("architecture.md"),
                        "# TODO app\n\nApproach: split work into auth → ui → tests.\n");
                Files.writeString(cwd.resolve("plan.json"), """
                        {"tasks":[
                          {"key":"auth","title":"Build auth module","description":"JWT-based session handling.","role":"dev","priority":100},
                          {"key":"ui","title":"Build UI","description":"List view + detail view.","role":"dev","priority":100},
                          {"key":"tests","title":"Write integration tests","description":"Cover auth and UI happy paths.","role":"tester","priority":100}
                        ],
                        "edges":[{"from":"auth","to":"ui"},{"from":"ui","to":"tests"}]}
                        """);
                finishOk(model, session, "Plan written.");
            }
            default -> {
                Files.writeString(cwd.resolve("FAKE_OUTPUT.md"), "written by the fake claude\n");
                if (Files.exists(cwd.resolve(".git"))) {
                    run(cwd, "git", "add", "-A");
                    run(cwd, "git", "commit", "-q", "-m", "fake: write FAKE_OUTPUT.md");
                }
                finishOk(model, session, "Done.");
            }
        }
    }

    private static void finishOk(String model, String session, String finalText) {
        assistant("msg_1", model, "Working on it", 1200, 80);
        emit("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"toolu_1\","
                + "\"type\":\"tool_result\",\"content\":\"ok\"}]},\"session_id\":" + q(session) + "}");
        assistant("msg_2", model, finalText, 1500, 40);
        result(false, "success", finalText, "0.01", session);
    }

    private static void assistant(String id, String model, String text, long inputTokens, long outputTokens) {
        emit("{\"type\":\"assistant\",\"message\":{\"id\":" + q(id) + ",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":" + q(model) + ",\"content\":[{\"type\":\"text\",\"text\":" + q(text) + "}],"
                + "\"usage\":{\"input_tokens\":" + inputTokens + ",\"cache_creation_input_tokens\":0,"
                + "\"cache_read_input_tokens\":0,\"output_tokens\":" + outputTokens + "}}}");
    }

    private static void result(boolean isError, String subtype, String text, String cost, String session) {
        emit("{\"type\":\"result\",\"subtype\":" + q(subtype) + ",\"is_error\":" + isError + ",\"num_turns\":2,"
                + "\"result\":" + q(text) + ",\"session_id\":" + q(session)
                + (cost == null ? "" : ",\"total_cost_usd\":" + cost)
                + ",\"usage\":{\"input_tokens\":2700,\"cache_creation_input_tokens\":0,\"cache_read_input_tokens\":0,"
                + "\"output_tokens\":120}}");
    }

    private static void emit(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static void sleepForever() throws InterruptedException {
        Thread.sleep(Long.MAX_VALUE);
    }

    private static void run(Path cwd, String... cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        p.waitFor();
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }

    private static String jsonArray(List<String> items) {
        List<String> quoted = new ArrayList<>();
        for (String s : items) quoted.add(q(s));
        return "[" + String.join(",", quoted) + "]";
    }

    private static String jsonObject(Map<String, String> map) {
        List<String> pairs = new ArrayList<>();
        map.forEach((k, v) -> pairs.add(q(k) + ":" + q(v)));
        return "{" + String.join(",", pairs) + "}";
    }
}
