package com.ai.orange.mcp;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.harness.FakeClaude;
import com.ai.orange.json.Json;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunOutcome;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunStatus;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WorkerMcpIT {

    @LocalServerPort int port;
    @Autowired RunRepository runs;
    @Autowired TaskService taskService;
    @Autowired WorkerMcpConfigWriter configWriter;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        dsl.deleteFrom(RUNS).execute();
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void missingOrUnknownTokenIs401() throws Exception {
        HttpResponse<String> none = new McpTestClient(url(), null).post(initializeBody());
        assertThat(none.statusCode()).isEqualTo(401);
        assertThat(none.body()).contains("invalid or inactive run token");

        HttpResponse<String> unknown = new McpTestClient(url(), RunTokens.newToken()).post(initializeBody());
        assertThat(unknown.statusCode()).isEqualTo(401);
    }

    @Test
    void finishedRunTokenIs401() throws Exception {
        String token = RunTokens.newToken();
        Run run = runningRun(RunKind.IMPLEMENT, token);
        runs.finish(run.id(), RunStatus.SUCCEEDED, null, BigDecimal.ZERO, 0, 0, 0, null);

        assertThat(new McpTestClient(url(), token).post(initializeBody()).statusCode()).isEqualTo(401);
    }

    @Test
    void implementRunListsOnlyItsTools() throws Exception {
        String token = RunTokens.newToken();
        runningRun(RunKind.IMPLEMENT, token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();
        assertThat(client.listTools()).containsExactlyInAnyOrder("report_result", "request_human");
    }

    @Test
    void diffReviewRunListsOnlySubmitReview() throws Exception {
        String token = RunTokens.newToken();
        runningRun(RunKind.DIFF_REVIEW, token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();
        assertThat(client.listTools()).containsExactly("submit_review");
    }

    @Test
    void reportResultRecordsTheOutcome() throws Exception {
        String token = RunTokens.newToken();
        Run run = runningRun(RunKind.IMPLEMENT, token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        JsonNode result = client.callTool("report_result", args().put("status", "done").put("summary", "Added X"));

        assertThat(McpTestClient.failed(result)).as(result.toString()).isFalse();
        assertThat(McpTestClient.text(result)).isEqualTo("recorded");
        assertThat(outcome(run)).isEqualTo(new RunOutcome.Result("done", "Added X", null));
    }

    @Test
    void requestHumanRecordsABlockedOutcome() throws Exception {
        String token = RunTokens.newToken();
        Run run = runningRun(RunKind.IMPLEMENT, token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        client.callTool("request_human", args().put("question", "Postgres or SQLite?"));

        assertThat(outcome(run)).isEqualTo(new RunOutcome.Result("blocked", "Postgres or SQLite?", "Postgres or SQLite?"));
    }

    @Test
    void submitReviewRecordsGaps() throws Exception {
        String token = RunTokens.newToken();
        Run run = runningRun(RunKind.DIFF_REVIEW, token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        ObjectNode a = args().put("verdict", "gaps").put("summary", "Two gaps");
        a.putArray("gaps").addObject().put("path", "README.md").put("line", 1).put("body", "typo");
        ((com.fasterxml.jackson.databind.node.ArrayNode) a.get("gaps")).addObject().put("body", "no tests");
        JsonNode result = client.callTool("submit_review", a);

        assertThat(McpTestClient.failed(result)).as(result.toString()).isFalse();
        RunOutcome.Review review = (RunOutcome.Review) outcome(run);
        assertThat(review.verdict()).isEqualTo("gaps");
        assertThat(review.gaps()).hasSize(2);
        assertThat(review.gaps().get(0).path()).isEqualTo("README.md");
        assertThat(review.gaps().get(0).line()).isEqualTo(1);
        assertThat(review.gaps().get(1).path()).isNull();
    }

    @Test
    void invalidVerdictIsAnErrorAndRecordsNothing() throws Exception {
        String token = RunTokens.newToken();
        Run run = runningRun(RunKind.DIFF_REVIEW, token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        JsonNode result = client.callTool("submit_review", args().put("verdict", "maybe").put("summary", "x"));

        assertThat(McpTestClient.failed(result)).isTrue();
        assertThat(runs.findById(run.id()).orElseThrow().outcome()).isNull();
    }

    @Test
    void gapsVerdictNeedsAGap() throws Exception {
        String token = RunTokens.newToken();
        Run run = runningRun(RunKind.DIFF_REVIEW, token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        ObjectNode a = args().put("verdict", "gaps").put("summary", "something is off");
        a.putArray("gaps");
        assertThat(McpTestClient.failed(client.callTool("submit_review", a))).isTrue();
        assertThat(runs.findById(run.id()).orElseThrow().outcome()).isNull();
    }

    @Test
    void blankSummaryIsAnError() throws Exception {
        String token = RunTokens.newToken();
        Run run = runningRun(RunKind.IMPLEMENT, token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        assertThat(McpTestClient.failed(client.callTool("report_result", args().put("status", "done").put("summary", "  "))))
                .isTrue();
        assertThat(runs.findById(run.id()).orElseThrow().outcome()).isNull();
    }

    @Test
    void implementTokenCannotSubmitReview() throws Exception {
        String token = RunTokens.newToken();
        Run run = runningRun(RunKind.IMPLEMENT, token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        JsonNode result = client.callTool("submit_review", args().put("verdict", "pass").put("summary", "fine"));

        assertThat(McpTestClient.failed(result)).isTrue();
        assertThat(runs.findById(run.id()).orElseThrow().outcome()).isNull();
    }

    @Test
    void briefRunListsOnlySubmitBrief() throws Exception {
        String token = RunTokens.newToken();
        briefRun(token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();
        assertThat(client.listTools()).containsExactly("submit_brief");
    }

    @Test
    void submitBriefRecordsTheLines() throws Exception {
        String token = RunTokens.newToken();
        Run run = briefRun(token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        ObjectNode a = briefArgs("Alpha", "beta");
        a.putArray("focus").addObject().put("project", "alpha").put("item", "Answer the session")
                .put("why", "It has waited since morning.");
        JsonNode result = client.callTool("submit_brief", a);

        assertThat(McpTestClient.failed(result)).as(result.toString()).isFalse();
        assertThat(McpTestClient.text(result)).isEqualTo("recorded");
        JsonNode content = ((RunOutcome.Brief) outcome(run)).content();
        assertThat(content.path("projects")).hasSize(2);
        assertThat(content.path("projects").path(0).path("project").asText()).as("stored as the run names it")
                .isEqualTo("alpha");
        assertThat(content.path("projects").path(0).path("status_line").asText()).isEqualTo("Alpha moved.");
        assertThat(content.path("projects").path(1).path("status_line").asText()).isEqualTo("beta moved.");
        assertThat(content.path("focus").path(0).path("item").asText()).isEqualTo("Answer the session");
        assertThat(content.path("urgent")).isEmpty();
    }

    @Test
    void aBriefAboutAnUnknownProjectIsRefusedAndRecordsNothing() throws Exception {
        String token = RunTokens.newToken();
        Run run = briefRun(token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        ObjectNode a = briefArgs("alpha", "beta", "no-such-project");
        a.putArray("focus");
        JsonNode result = client.callTool("submit_brief", a);

        assertThat(McpTestClient.failed(result)).isTrue();
        assertThat(McpTestClient.text(result)).contains("no-such-project").contains("alpha").contains("beta");
        assertThat(runs.findById(run.id()).orElseThrow().outcome()).isNull();
    }

    @Test
    void aBriefThatLeavesAProjectOutIsRefusedAndCanBeCorrected() throws Exception {
        String token = RunTokens.newToken();
        Run run = briefRun(token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        ObjectNode partial = briefArgs("alpha");
        partial.putArray("focus");
        JsonNode refused = client.callTool("submit_brief", partial);

        assertThat(McpTestClient.failed(refused)).isTrue();
        assertThat(McpTestClient.text(refused)).contains("beta");
        assertThat(runs.findById(run.id()).orElseThrow().outcome()).isNull();

        // The refusal says what to correct; the corrected call lands.
        ObjectNode whole = briefArgs("alpha", "beta");
        whole.putArray("focus");
        assertThat(McpTestClient.failed(client.callTool("submit_brief", whole))).isFalse();
        assertThat(outcome(run)).isInstanceOf(RunOutcome.Brief.class);
    }

    @Test
    void aBriefWithNoArgumentsIsRefused() throws Exception {
        String token = RunTokens.newToken();
        Run run = briefRun(token);
        McpTestClient client = new McpTestClient(url(), token);
        client.initialize();

        JsonNode result = client.callTool("submit_brief", args());

        assertThat(McpTestClient.failed(result)).isTrue();
        assertThat(McpTestClient.text(result)).contains("projects");
        assertThat(runs.findById(run.id()).orElseThrow().outcome()).isNull();
    }

    @Test
    void submitBriefBelongsToBriefRunsOnly() throws Exception {
        String implementToken = RunTokens.newToken();
        Run implement = runningRun(RunKind.IMPLEMENT, implementToken);
        McpTestClient implementer = new McpTestClient(url(), implementToken);
        implementer.initialize();
        ObjectNode a = briefArgs("alpha", "beta");
        a.putArray("focus");

        assertThat(McpTestClient.failed(implementer.callTool("submit_brief", a))).isTrue();
        assertThat(runs.findById(implement.id()).orElseThrow().outcome()).isNull();

        String briefToken = RunTokens.newToken();
        Run brief = briefRun(briefToken);
        McpTestClient writer = new McpTestClient(url(), briefToken);
        writer.initialize();

        assertThat(McpTestClient.failed(writer.callTool("report_result",
                args().put("status", "done").put("summary", "Wrote it")))).isTrue();
        assertThat(runs.findById(brief.id()).orElseThrow().outcome()).isNull();
    }

    @Test
    void aTokenOnlyWritesItsOwnRun() throws Exception {
        String tokenA = RunTokens.newToken();
        String tokenB = RunTokens.newToken();
        Run a = runningRun(RunKind.IMPLEMENT, tokenA);
        Run b = runningRun(RunKind.IMPLEMENT, tokenB);
        McpTestClient client = new McpTestClient(url(), tokenA);
        client.initialize();

        client.callTool("report_result", args().put("status", "failed").put("summary", "could not"));

        assertThat(outcome(a)).isEqualTo(new RunOutcome.Result("failed", "could not", null));
        assertThat(runs.findById(b.id()).orElseThrow().outcome()).isNull();
    }

    @Test
    void rawTokenIsNeverStored() {
        String token = RunTokens.newToken();
        Run run = runningRun(RunKind.IMPLEMENT, token);
        String row = dsl.resultQuery("SELECT r::text FROM runs r WHERE r.id = ?", run.id()).fetchOne(0, String.class);
        assertThat(row).doesNotContain(token);
        assertThat(dsl.fetchValue(dsl.select(RUNS.MCP_TOKEN_HASH).from(RUNS).where(RUNS.ID.eq(run.id()))))
                .isEqualTo(RunTokens.hash(token));
        assertThat(RunTokens.hash(token)).matches("[0-9a-f]{64}");
    }

    @Test
    void fakeClaudeReportsThroughTheEndpoint() throws Exception {
        String token = RunTokens.newToken();
        Run run = runningRun(RunKind.IMPLEMENT, token);
        Path repo = gitRepo();
        Path configDir = Files.createTempDirectory("fake-config-");
        Path mcpConfig = configWriter.write(configDir, token);

        List<String> cmd = new ArrayList<>(FakeClaude.command());
        cmd.addAll(List.of("-p", "--model", "claude-sonnet-5-5", "--session-id", UUID.randomUUID().toString(),
                "--mcp-config", mcpConfig.toString(),
                "--allowedTools", "Read", "mcp__orange__report_result", "mcp__orange__request_human"));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true);
        pb.environment().put("CLAUDE_CONFIG_DIR", configDir.toString());
        Process p = pb.start();
        p.getOutputStream().write("Add a greeting\nmore detail".getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();

        assertThat(p.exitValue()).as(output).isZero();
        assertThat(outcome(run)).isEqualTo(new RunOutcome.Result("done", "Implemented: Add a greeting", null));
        assertThat(Files.readString(configDir.resolve("fake-prompt.txt"))).startsWith("Add a greeting");
        assertThat(git(repo, "log", "-1", "--pretty=%s")).isEqualTo("fake: Add a greeting");
    }

    // ─────────────────────────── helpers ───────────────────────────

    private Run runningRun(RunKind kind, String token) {
        UUID task = taskService.createGraph(
                List.of(new TaskDef("t-" + UUID.randomUUID(), "T", null, "implementer", null, null)),
                List.of()).get(0).id();
        Run run = runs.insertRunning(kind, "implementer", task, "w", Duration.ofMinutes(5), JSONB.valueOf("{}"));
        runs.recordRoute(run.id(), "medium", "claude-sonnet-5-5", "high", "claude-code", "subscription",
                new BigDecimal("4"), null, RunTokens.hash(token), null);
        return run;
    }

    /** A running brief run, which has no task, for the projects alpha and beta. */
    private Run briefRun(String token) {
        Run run = runs.insertRunning(RunKind.BRIEF, "brief", null, "w", Duration.ofMinutes(5),
                JSONB.valueOf("{\"projects\":[\"alpha\",\"beta\"]}"));
        runs.recordRoute(run.id(), "medium", "claude-sonnet-5-5", "low", "claude-code", "subscription",
                new BigDecimal("4"), null, RunTokens.hash(token), null);
        return run;
    }

    /** {@code submit_brief} arguments with a status line per project and no focus yet. */
    private static ObjectNode briefArgs(String... projects) {
        ObjectNode a = args();
        for (String p : projects) a.withArray("projects").addObject().put("project", p).put("status_line", p + " moved.");
        return a;
    }

    private RunOutcome outcome(Run run) {
        return RunOutcome.parse(runs.findById(run.id()).orElseThrow().outcome()).orElseThrow();
    }

    private String url() {
        return "http://127.0.0.1:" + port + "/mcp/worker";
    }

    private static ObjectNode args() {
        return Json.mapper().createObjectNode();
    }

    private static String initializeBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\""
                + McpTestClient.PROTOCOL_VERSION + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"t\",\"version\":\"1\"}}}";
    }

    private static Path gitRepo() throws Exception {
        Path repo = Files.createTempDirectory("mcp-it-repo-");
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "t@e");
        git(repo, "config", "user.name", "T");
        Files.writeString(repo.resolve("README.md"), "hi\n");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "initial");
        return repo;
    }

    private static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
        return out;
    }
}
