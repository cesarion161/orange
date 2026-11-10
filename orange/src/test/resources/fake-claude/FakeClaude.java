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
 * it reviews and passes, with {@code mcp__orange__judge_continue} allowed it answers the session,
 * and with {@code mcp__orange__submit_brief} allowed it writes a brief: a status line for every
 * {@code ## Project: <name>} line of its prompt and one focus item. The brief scenarios with a
 * marker: {@code brief-urgent} (one urgent entry as well), {@code brief-unknown-project} (a call
 * that is refused, and nothing after it) and {@code brief-corrected} (that call, then a valid one).
 *
 * <p>{@code probe} says what the run can see: for every prompt line {@code probe: <path>}
 * (indented or not) it writes {@code <path>=visible} or {@code <path>=hidden} to
 * {@code fake-probe.txt} under {@code $CLAUDE_CONFIG_DIR}, then implements like the default.
 *
 * <p>{@code commit-hang} implements like the default, writes {@code fake-committed.txt} under
 * {@code $CLAUDE_CONFIG_DIR} and then hangs without reporting. {@code rewind} resets its branch
 * to the commit before the one it started from and reports {@code done}.
 *
 * <p>Every scenario records its arguments, environment and prompt as {@code fake-args.json},
 * {@code fake-env.json} and {@code fake-prompt.txt} under {@code $CLAUDE_CONFIG_DIR}; MCP replies
 * go to {@code fake-mcp.log}, after a {@code POST <url>} line per tool call.
 */
public class FakeClaude {

    private static final Pattern MARKER = Pattern.compile("\\[\\[fake:([a-z-]+)]]");
    /** How a brief prompt names the projects it wants a line for. */
    private static final Pattern PROJECT = Pattern.compile("(?m)^## Project: (.+)$");
    /** What the {@code probe} scenario looks for. */
    private static final Pattern PROBE = Pattern.compile("(?m)^\\s*probe: (.+)$");
    private static final String UNKNOWN_PROJECT_BRIEF = "{\"projects\":[{\"project\":\"no-such-project\","
            + "\"status_line\":\"All fine.\"}],\"focus\":[]}";

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
        else if (allowed.contains("mcp__orange__judge_continue")) scenario = "judge-continue";
        else if (allowed.contains("mcp__orange__submit_brief")) scenario = "brief";
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
            case "probe" -> {
                StringBuilder seen = new StringBuilder();
                Matcher probe = PROBE.matcher(prompt);
                while (probe.find()) {
                    String path = probe.group(1).strip();
                    seen.append(path).append(Files.exists(Path.of(path)) ? "=visible" : "=hidden").append('\n');
                }
                Files.writeString(configDir.resolve("fake-probe.txt"), seen.toString());
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
            case "judge-continue" -> {
                callTool("judge_continue", "{\"message\":\"Yes, proceed with option 1.\",\"rationale\":"
                        + "\"It fits the project's goals.\",\"confidence\":0.93}");
                finishOk(model, session, "Answered.");
            }
            case "judge-unsure" -> {
                callTool("judge_continue", "{\"message\":\"Probably option 2.\",\"rationale\":"
                        + "\"Nothing recorded settles it.\",\"confidence\":0.4}");
                finishOk(model, session, "Answered, unsure.");
            }
            case "judge-escalate" -> {
                callTool("judge_escalate", "{\"question\":\"The session wants to delete the production table; allow it?\","
                        + "\"proposed_answer\":\"No, archive it instead.\",\"urgency\":\"urgent\","
                        + "\"rationale\":\"Deleting data is irreversible.\"}");
                finishOk(model, session, "Escalated.");
            }
            case "judge-verify" -> {
                callTool("judge_verify", "{\"rationale\":\"It says the work is done and committed.\"}");
                finishOk(model, session, "Finished work.");
            }
            case "judge-idle" -> {
                callTool("judge_idle", "{\"reason\":\"A status note; nothing is asked.\"}");
                finishOk(model, session, "Nothing to do.");
            }
            case "judge-propose" -> {
                callTool("propose_item", "{\"kind\":\"decision\",\"text\":\"Retries use exponential backoff.\"}");
                callTool("judge_continue", "{\"message\":\"Go ahead.\",\"rationale\":\"Routine.\",\"confidence\":0.9}");
                finishOk(model, session, "Answered and proposed.");
            }
            case "brief" -> {
                callTool("submit_brief", briefArguments(briefProjects(prompt), ""));
                finishOk(model, session, "Brief written.");
            }
            case "brief-urgent" -> {
                List<String> projects = briefProjects(prompt);
                String urgent = projects.isEmpty() ? "" : "{\"project\":" + q(projects.get(0))
                        + ",\"title\":\"The milestone is close with nothing closed\",\"why\":\"It is due tomorrow.\"}";
                callTool("submit_brief", briefArguments(projects, urgent));
                finishOk(model, session, "Brief written, with something urgent.");
            }
            case "brief-unknown-project" -> {
                callTool("submit_brief", UNKNOWN_PROJECT_BRIEF);
                finishOk(model, session, "Tried a project that is not there.");
            }
            case "brief-corrected" -> {
                callTool("submit_brief", UNKNOWN_PROJECT_BRIEF);
                callTool("submit_brief", briefArguments(briefProjects(prompt), ""));
                finishOk(model, session, "Brief written at the second try.");
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
            case "commit-hang" -> {
                // Work that is committed, and then a command that never ends: what a kill finds.
                implement(cwd, prompt, firstLine);
                Files.writeString(configDir.resolve("fake-committed.txt"), "committed\n");
                assistant("msg_1", model, "Committed; now running a long command", 100, 20);
                sleepForever();
            }
            case "rewind" -> {
                // An agent that throws away the commit it started from and says it is done.
                run(cwd, "git", "reset", "-q", "--hard", "HEAD~1");
                callTool("report_result", "{\"status\":\"done\",\"summary\":\"Started over.\"}");
                finishOk(model, session, "Done.");
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
                // For the test to look for: the token is otherwise only in memory and in mcp.json.
                Files.writeString(configDir.resolve("fake-token.txt"), runToken);
                String dump = "CLAUDE_CODE_OAUTH_TOKEN=" + token + "\nANTHROPIC_AUTH_TOKEN=" + key
                        + "\nAuthorization: Bearer " + runToken;
                assistant("msg_1", model, "Let me check the environment: " + token + " " + key + " " + runToken, 100, 20);
                emit("{\"type\":\"user\",\"message\":{\"role\":\"user\",\"content\":[{\"tool_use_id\":\"toolu_1\","
                        + "\"type\":\"tool_result\",\"content\":" + q(dump) + "}]},\"session_id\":" + q(session) + "}");
                System.err.println("debug: token=" + token + " key=" + key + " run=" + runToken);
                System.err.flush();
                assistant("msg_2", model, "Found it: " + token + key + runToken, 1500, 40);
                if (mcpConfig != null) {
                    callTool("report_result", "{\"status\":\"done\",\"summary\":"
                            + q("the tokens are " + token + " " + key + " " + runToken) + "}");
                }
                result(false, "success", "Done, the token is " + token + key + runToken, "0.01", session);
            }
            case "plan" -> {
                Files.writeString(cwd.resolve("architecture.md"),
                        "# TODO app\n\nApproach: split work into auth → ui → tests.\n");
                Files.writeString(cwd.resolve("plan.json"), """
                        {"tasks":[
                          {"key":"auth","title":"Build auth module","description":"JWT-based session handling.","role":"implementer","priority":100},
                          {"key":"ui","title":"Build UI","description":"List view + detail view.","role":"implementer","priority":100},
                          {"key":"tests","title":"Write integration tests","description":"Cover auth and UI happy paths.","role":"implementer","priority":100}
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

    /** The projects a brief prompt wants a line for, in its order. */
    private static List<String> briefProjects(String prompt) {
        List<String> names = new ArrayList<>();
        Matcher m = PROJECT.matcher(prompt);
        while (m.find()) names.add(m.group(1).strip());
        return names;
    }

    /**
     * {@code submit_brief} arguments: a status line per project and one focus item for the first.
     *
     * @param urgentJson the entries of {@code urgent} as a JSON fragment; empty for none
     */
    private static String briefArguments(List<String> projects, String urgentJson) {
        List<String> lines = new ArrayList<>();
        for (String p : projects) {
            lines.add("{\"project\":" + q(p) + ",\"status_line\":" + q(p + ": steady, nothing blocked.") + "}");
        }
        String focus = projects.isEmpty() ? "" : "{\"project\":" + q(projects.get(0))
                + ",\"item\":\"Answer the open question\",\"why\":\"A session waits on it.\"}";
        return "{\"projects\":[" + String.join(",", lines) + "],\"focus\":[" + focus + "],\"urgent\":["
                + urgentJson + "]}";
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
        log("POST " + url);
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
