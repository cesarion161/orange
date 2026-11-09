import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A stand-in for {@code claude -p --output-format stream-json} used by orange's tests.
 * Launched as a single-file source program (JDK only, no package). It reads the prompt from
 * stdin and prints realistic stream-json lines.
 *
 * <p>The scenario comes from a {@code [[fake:<name>]]} marker in the prompt or in the
 * {@code --append-system-prompt} text. Without a marker it acts like the run it was started as:
 * with {@code mcp__orange__report_result} allowed it implements (edits, commits and reports
 * {@code done} through the worker MCP endpoint), with {@code mcp__orange__submit_review} allowed
 * it reviews and passes.
 *
 * <p>Every scenario records its arguments, environment and prompt as {@code fake-args.json},
 * {@code fake-env.json} and {@code fake-prompt.txt} under {@code $CLAUDE_CONFIG_DIR}; MCP replies
 * go to {@code fake-mcp.log}.
 */
public class FakeClaude {

    private static final Pattern MARKER = Pattern.compile("\\[\\[fake:([a-z-]+)]]");

    private static Path configDir;
    private static Path mcpConfig;

    public static void main(String[] args) throws Exception {
        String model = "claude-sonnet-5-5";
        String system = "";
        String session = "fake-session";
        List<String> allowed = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--model" -> model = args[++i];
                case "--append-system-prompt" -> system = args[++i];
                case "--session-id" -> session = args[++i];
                case "--mcp-config" -> mcpConfig = Path.of(args[++i]);
                case "--allowedTools" -> {
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) allowed.add(args[++i]);
                }
                default -> { }
            }
        }
        String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);

        configDir = Path.of(System.getenv().getOrDefault("CLAUDE_CONFIG_DIR", "."));
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("fake-args.json"), jsonArray(List.of(args)));
        Files.writeString(configDir.resolve("fake-env.json"), jsonObject(System.getenv()));
        Files.writeString(configDir.resolve("fake-prompt.txt"), prompt);

        Matcher m = MARKER.matcher(prompt + "\n" + system);
        String scenario;
        if (m.find()) scenario = m.group(1);
        else if (allowed.contains("mcp__orange__report_result")) scenario = "implement";
        else if (allowed.contains("mcp__orange__submit_review")) scenario = "review";
        else scenario = "success";
        Path cwd = Path.of("").toAbsolutePath();
        String firstLine = prompt.lines().filter(l -> !l.isBlank()).findFirst().orElse("work").replaceFirst("^#+\\s*", "").trim();

        emit("{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":" + q(session) + ",\"model\":" + q(model)
                + ",\"cwd\":" + q(cwd.toString()) + ",\"tools\":[\"Read\",\"Edit\",\"Bash\"]}");

        switch (scenario) {
            case "implement" -> {
                implement(cwd, prompt, firstLine);
                callTool("report_result", "{\"status\":\"done\",\"summary\":" + q("Implemented: " + firstLine) + "}");
                finishOk(model, session, "Done.");
            }
            case "no-commit" -> {
                callTool("report_result", "{\"status\":\"done\",\"summary\":\"Nothing to change.\"}");
                finishOk(model, session, "Done without changes.");
            }
            case "report-failed" -> {
                implement(cwd, prompt, firstLine);
                callTool("report_result", "{\"status\":\"failed\",\"summary\":\"The tests would not pass.\"}");
                finishOk(model, session, "Gave up.");
            }
            case "blocked" -> {
                callTool("request_human", "{\"question\":\"Which database should I use, Postgres or SQLite?\"}");
                finishOk(model, session, "Waiting for an answer.");
            }
            case "no-outcome" -> {
                implement(cwd, prompt, firstLine);
                finishOk(model, session, "Done, I think.");
            }
            case "dirty" -> {
                implement(cwd, prompt, firstLine);
                Files.writeString(cwd.resolve("scratch.txt"), "left behind\n");
                callTool("report_result", "{\"status\":\"done\",\"summary\":\"Done.\"}");
                finishOk(model, session, "Done.");
            }
            case "wrong-tool" -> {
                callTool("submit_review", "{\"verdict\":\"pass\",\"summary\":\"I approve of myself.\"}");
                finishOk(model, session, "Tried the wrong tool.");
            }
            case "review" -> {
                callTool("submit_review", "{\"verdict\":\"pass\",\"summary\":\"Looks good\",\"gaps\":[]}");
                finishOk(model, session, "Reviewed.");
            }
            case "review-gaps" -> {
                callTool("submit_review", "{\"verdict\":\"gaps\",\"summary\":\"Two gaps\",\"gaps\":["
                        + "{\"path\":\"README.md\",\"line\":1,\"body\":\"The heading has a typo.\"},"
                        + "{\"body\":\"No test covers the change.\"}]}");
                finishOk(model, session, "Reviewed.");
            }
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
            case "auth-failed" -> {
                // What Claude Code 2.1.319 prints for a rejected token (ClaudeCliContractLiveIT checks the real one).
                for (int attempt = 1; attempt <= 2; attempt++) {
                    emit("{\"type\":\"system\",\"subtype\":\"api_retry\",\"attempt\":" + attempt + ",\"max_retries\":10,"
                            + "\"retry_delay_ms\":600,\"error_status\":401,\"error\":\"authentication_failed\","
                            + "\"session_id\":" + q(session) + "}");
                }
                String text = "Failed to authenticate. API Error: 401 OAuth access token is invalid.";
                assistant("msg_1", "<synthetic>", text, 0, 0);
                result(true, "success", text, "0", session);
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
            case "leak-env" -> {
                // An agent that runs `env` and cats its MCP config while debugging: its credentials
                // and its run token show up in a tool result, in its own text, in the final result
                // and on stderr.
                String token = System.getenv().getOrDefault("CLAUDE_CODE_OAUTH_TOKEN", "");
                String key = System.getenv().getOrDefault("ANTHROPIC_AUTH_TOKEN", "");
                String runToken = mcpConfig == null ? "" : mcpField("Authorization").replaceFirst("^Bearer ", "");
                String dump = "CLAUDE_CODE_OAUTH_TOKEN=" + token + "\nANTHROPIC_AUTH_TOKEN=" + key
                        + "\nAuthorization: Bearer " + runToken;
                assistant("msg_1", model, "Let me check the environment: " + token + " " + key + " " + runToken, 100, 20);
                emit("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"toolu_1\","
                        + "\"type\":\"tool_result\",\"content\":" + q(dump) + "}]},\"session_id\":" + q(session) + "}");
                System.err.println("debug: token=" + token + " key=" + key + " run=" + runToken);
                System.err.flush();
                assistant("msg_2", model, "Found it: " + token + key + runToken, 1500, 40);
                if (mcpConfig != null) {
                    callTool("report_result", "{\"status\":\"done\",\"summary\":" + q("the token is " + runToken) + "}");
                }
                result(false, "success", "Done, the token is " + token + key + runToken, "0.01", session);
            }
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

    /** The default implementer's work: one more line in orange-fake.txt (plus fixed.txt for a fix), committed. */
    private static void implement(Path cwd, String prompt, String firstLine) throws IOException, InterruptedException {
        Files.writeString(cwd.resolve("orange-fake.txt"), firstLine + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (prompt.contains("## Feedback to address")) {
            Files.writeString(cwd.resolve("fixed.txt"), "fixed after feedback\n");
        }
        if (Files.exists(cwd.resolve(".git"))) {
            run(cwd, "git", "add", "-A");
            run(cwd, "git", "-c", "user.email=fake@orange", "-c", "user.name=fake", "commit", "-q", "-m", "fake: " + firstLine);
        }
    }

    // ─────────────────────────── worker MCP client ───────────────────────────

    /** Calls a worker MCP tool the way Claude Code would: initialize, initialized, then tools/call. */
    private static void callTool(String name, String argumentsJson) throws IOException, InterruptedException {
        if (mcpConfig == null) {
            log("no --mcp-config; cannot call " + name);
            return;
        }
        String url = mcpField("url");
        String auth = mcpField("Authorization");
        HttpClient http = HttpClient.newHttpClient();
        post(http, url, auth, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"fake-claude\",\"version\":\"1\"}}}");
        post(http, url, auth, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        post(http, url, auth, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":" + q(name)
                + ",\"arguments\":" + argumentsJson + "}}");
    }

    private static void post(HttpClient http, String url, String auth, String body) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2025-06-18")
                .header("Authorization", auth)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
        log(res.statusCode() + " " + res.body().replace('\n', ' '));
    }

    /** Pulls a string field out of the MCP config JSON (enough for the one-server file orange writes). */
    private static String mcpField(String field) throws IOException {
        String json = Files.readString(mcpConfig);
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        if (!m.find()) throw new IOException("no " + field + " in " + mcpConfig);
        return m.group(1).replace("\\/", "/");
    }

    private static void log(String line) throws IOException {
        Files.writeString(configDir.resolve("fake-mcp.log"), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    // ─────────────────────────── stream-json ───────────────────────────

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
