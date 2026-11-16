package com.ai.orange.mcp;

import static com.ai.orange.db.jooq.Tables.BRIEFS;
import static com.ai.orange.db.jooq.Tables.PROJECTS;
import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.json.Json;
import com.ai.orange.planner.Plan;
import com.ai.orange.planner.PlanRepository;
import com.ai.orange.project.Conventions;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.TaskPlacement;
import com.ai.orange.repo.FakeGithub;
import com.ai.orange.repo.Mirrors;
import com.ai.orange.repo.RepoKey;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunEventRepository;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunTestSupport;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.session.NeutralEvent;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionService;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The steering tools over real MCP: the space, the inbox, the sessions, plans, tasks and the scheduler, as a
 * Claude Code session uses them. The fake Claude Code plans; a directory of bare repositories stands in for
 * GitHub, and a local HTTP server for GitHub's REST API, which creates a repository there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SteeringMcpIT {

    static final FakeGithub GITHUB = new FakeGithub(RunTestSupport.tempDir("steering-gh-"));
    static final String TOKEN = "tok-steering-test";
    /** GitHub's REST API: who the token is, and creating a repo under that account. */
    static final HttpServer API;
    static final List<String> API_CALLS = new CopyOnWriteArrayList<>();

    static {
        try {
            API = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        API.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            API_CALLS.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                    + ex.getRequestHeaders().getFirst("Authorization"));
            int status = 404;
            String answer = "{\"message\":\"Not Found\"}";
            if (ex.getRequestURI().getPath().equals("/user")) {
                status = 200;
                answer = "{\"login\":\"me\"}";
            } else if (ex.getRequestURI().getPath().equals("/user/repos")) {
                String repo = Json.mapper().readTree(body).path("name").asText();
                // A "ghost" repo is created on GitHub's word only: orange cannot clone it afterwards.
                if (!repo.contains("ghost")) GITHUB.create("me/" + repo);
                status = 201;
                answer = "{\"full_name\":\"me/" + repo + "\"}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        API.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RunTestSupport.tempDir("steering-runs-"), RunTestSupport.baseRepo(),
                RunTestSupport.tempDir("steering-wt-"));
        r.add("orange.planner.output-root", () -> RunTestSupport.tempDir("steering-plans-").toString());
        r.add("orange.repos.root", () -> RunTestSupport.tempDir("steering-mirrors-").toString());
        r.add("orange.repos.github-url", GITHUB::url);
        r.add("orange.repos.tokens", () -> "me=" + TOKEN);
        r.add("orange.repos.github-api-url", () -> "http://127.0.0.1:" + API.getAddress().getPort());
    }

    @Value("${local.server.port}") int port;
    @Autowired SessionService sessions;
    @Autowired ProjectService projects;
    @Autowired AttentionRepository attention;
    @Autowired DSLContext dsl;
    @Autowired PlanRepository plans;
    @Autowired TaskService taskService;
    @Autowired RunRepository runs;
    @Autowired RunsProperties runsProps;
    @Autowired RunEventRepository runEvents;
    @Autowired EvidenceRepository evidence;
    @Autowired SchedulerPause pause;
    @Autowired Mirrors mirrors;
    @Autowired Conventions conventions;
    @Autowired TaskEventRepository taskEvents;

    McpTestClient mcp;
    String name;

    @BeforeEach
    void connect() throws Exception {
        mcp = new McpTestClient("http://localhost:" + port + "/mcp/steering", null);
        mcp.initialize();
        name = "steer-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void resume() {
        pause.resume();
    }

    static ObjectNode args() {
        return Json.mapper().createObjectNode();
    }

    JsonNode call(String tool, ObjectNode args) throws Exception {
        JsonNode result = mcp.callTool(tool, args);
        assertThat(McpTestClient.failed(result)).as(result.toString()).isFalse();
        return Json.mapper().readTree(McpTestClient.text(result));
    }

    String refused(String tool, ObjectNode args) throws Exception {
        JsonNode result = mcp.callTool(tool, args);
        assertThat(McpTestClient.failed(result)).as(result.toString()).isTrue();
        return McpTestClient.text(result);
    }

    Session stoppedSessionIn(String repoRoot) throws Exception {
        String sid = "steer-" + UUID.randomUUID();
        ObjectNode e = args().put("event_id", UUID.randomUUID().toString()).put("harness", "claude-code")
                .put("session_id", sid).put("type", "stopped").put("cwd", repoRoot).put("repo_root", repoRoot)
                .put("branch", "main");
        e.putObject("payload").put("last_message", "Shall I continue?");
        return sessions.apply(NeutralEvent.parse(e));
    }

    @Test
    void theToolsAreListed() throws Exception {
        assertThat(mcp.listTools()).containsExactlyInAnyOrder("orange_projects", "orange_project",
                "orange_project_update", "orange_add", "orange_set", "orange_accept", "orange_drop", "orange_done",
                "orange_attention", "orange_resolve", "orange_sessions", "orange_focus", "orange_plan", "orange_status",
                "orange_task", "orange_tail", "orange_cancel", "orange_pause", "orange_resume", "orange_project_create");
    }

    @Test
    void theLatestBriefIsReadWithoutWritingOne() throws Exception {
        int briefsBefore = dsl.fetchCount(BRIEFS);
        int runsBefore = dsl.fetchCount(RUNS, RUNS.KIND.eq(RunKind.BRIEF.dbValue()));
        // Never fresh: true here: a fresh brief would wait for a brief run.
        JsonNode focus = call("orange_focus", args());
        assertThat(focus.path("state").asText()).isIn("none", "ready");
        if ("none".equals(focus.path("state").asText())) {
            assertThat(focus.has("brief")).isFalse();
            assertThat(focus.path("hint").asText()).contains("fresh");
        } else {
            assertThat(focus.path("brief").path("id").asText()).isNotBlank();
            assertThat(focus.path("brief").path("text").asText()).contains("Waiting on you");
            assertThat(focus.path("brief").path("page").asText()).contains("/brief/").contains("?k=");
        }
        assertThat(call("orange_focus", args().put("fresh", false)).path("state").asText())
                .as("the same answer: nothing was written in between").isEqualTo(focus.path("state").asText());

        assertThat(dsl.fetchCount(BRIEFS)).as("reading writes no brief").isEqualTo(briefsBefore);
        assertThat(dsl.fetchCount(RUNS, RUNS.KIND.eq(RunKind.BRIEF.dbValue()))).as("and starts no brief run")
                .isEqualTo(runsBefore);
    }

    @Test
    void aProjectsSpaceIsBuiltAndRead() throws Exception {
        JsonNode created = call("orange_project_update", args().put("name", name).put("summary", "Steering test"));
        assertThat(created.path("status").asText()).isEqualTo("active");

        JsonNode goal = call("orange_add", args().put("project", name).put("kind", "goal").put("text", "Ship it"));
        call("orange_add", args().put("project", name).put("kind", "milestone").put("text", "Beta").put("due", "2026-12-01"));
        JsonNode better = call("orange_add", args().put("project", name).put("kind", "goal").put("text", "Ship it well")
                .put("supersedes", goal.path("id").asText()));
        ObjectNode set = args().put("project", name).put("key", "evidence");
        set.putArray("value").add("./gradlew test");
        call("orange_set", set);
        call("orange_set", args().put("project", name).put("key", "integration").put("value", "branch"));

        JsonNode space = call("orange_project", args().put("project", name));
        assertThat(space.path("summary").asText()).isEqualTo("Steering test");
        assertThat(space.path("goals")).hasSize(1);
        assertThat(space.path("goals").get(0).path("text").asText()).isEqualTo("Ship it well");
        assertThat(space.path("goals").get(0).path("supersedes").asText()).isEqualTo(goal.path("id").asText());
        assertThat(space.path("goals").get(0).path("author").asText()).isEqualTo("user");
        assertThat(space.path("milestones").get(0).path("due_at").asText()).startsWith("2026-12-01T23:59");
        assertThat(space.path("conventions")).hasSize(2);
        assertThat(space.path("conventions").get(0).path("value").get(0).asText()).isEqualTo("./gradlew test");
        assertThat(space.path("global_conventions").path("quiet-hours").get(0).asText()).isEqualTo("22:00");

        assertThat(call("orange_done", args().put("id", better.path("id").asText())).path("status").asText())
                .isEqualTo("done");
        assertThat(call("orange_project", args().put("project", name)).has("goals")).isFalse();
    }

    @Test
    void badCallsSayWhatToCorrect() throws Exception {
        assertThat(refused("orange_project", args().put("project", "no-such-" + name))).contains("no such project");
        call("orange_project_update", args().put("name", name));
        assertThat(refused("orange_add", args().put("project", name).put("kind", "wish").put("text", "x")))
                .contains("kind");
        assertThat(refused("orange_accept", args().put("id", "abc"))).contains("must be a full id");
        assertThat(refused("orange_add", args().put("project", name).put("kind", "milestone").put("text", "x")
                .put("due", "next week"))).contains("due must be");
        assertThat(refused("orange_project_update", args())).contains("give project");
    }

    @Test
    void aProposedProjectIsConfirmedThroughTheInbox() throws Exception {
        String repoRoot = "/tmp/" + name;
        Session s = stoppedSessionIn(repoRoot);

        JsonNode open = call("orange_attention", args());
        JsonNode proposed = null;
        JsonNode question = null;
        for (JsonNode item : open) {
            if (s.projectId().toString().equals(item.path("project_id").asText())) {
                if ("project_proposed".equals(item.path("kind").asText())) proposed = item;
                if ("session_question".equals(item.path("kind").asText())) question = item;
            }
        }
        assertThat(proposed).isNotNull();
        assertThat(question).isNotNull();
        assertThat(question.path("title").asText()).contains(name).contains("Shall I continue?");

        assertThat(refused("orange_resolve", args().put("id", proposed.path("id").asText()).put("answer", "maybe")))
                .contains("confirm or ignore");
        JsonNode resolved = call("orange_resolve", args().put("id", proposed.path("id").asText()).put("answer", "confirm"));
        assertThat(resolved.path("status").asText()).isEqualTo("resolved");
        assertThat(projects.find(name).orElseThrow().status()).isEqualTo(Project.ACTIVE);

        JsonNode listed = call("orange_projects", args());
        JsonNode row = null;
        for (JsonNode p : listed) if (name.equals(p.path("name").asText())) row = p;
        assertThat(row.path("sessions").asInt()).isEqualTo(1);
        assertThat(row.path("open_attention").asInt()).isEqualTo(1);

        JsonNode rows = call("orange_sessions", args().put("project", name));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("status").asText()).isEqualTo("stopped");
        assertThat(rows.get(0).path("waiting_reason").asText()).isEqualTo("question");

        call("orange_resolve", args().put("id", question.path("id").asText()).put("answer", "yes, continue"));
        AttentionItem answered = attention.findById(UUID.fromString(question.path("id").asText())).orElseThrow();
        assertThat(answered.resolution()).isEqualTo("yes, continue");
        assertThat(answered.resolvedBy()).isEqualTo("user");
    }

    @Test
    void attachingARepoMovesItAndItsSessions() throws Exception {
        String repoRoot = "/tmp/" + name + "-repo";
        Session s = stoppedSessionIn(repoRoot);
        call("orange_project_update", args().put("name", name));

        JsonNode updated = call("orange_project_update", args().put("project", name).put("add_repo", repoRoot));
        assertThat(updated.path("repos")).hasSize(1);
        Project target = projects.find(name).orElseThrow();
        assertThat(call("orange_sessions", args().put("project", name)).get(0).path("id").asText())
                .isEqualTo(s.id().toString());
        // The next event from that repo lands in the project that owns it now.
        assertThat(stoppedSessionIn(repoRoot).projectId()).isEqualTo(target.id());
    }

    @Test
    void attachingAGithubRepoByOwnerAndNameListsIt() throws Exception {
        call("orange_project_update", args().put("name", name));
        JsonNode updated = call("orange_project_update", args().put("project", name).put("add_repo", "Me/" + name));
        assertThat(updated.path("github_repos")).hasSize(1);
        assertThat(updated.path("github_repos").get(0).path("key").asText()).isEqualTo("me/" + name.toLowerCase());
        assertThat(call("orange_project", args().put("project", name)).path("github_repos")).hasSize(1);
    }

    @Test
    void renamingAProposedProjectToActiveAnswersItsQuestion() throws Exception {
        Session s = stoppedSessionIn("/tmp/" + name + "-x");
        call("orange_project_update", args().put("project", s.projectId().toString()).put("name", name)
                .put("status", "active"));
        assertThat(attention.findByKey("project:" + s.projectId() + ":proposed").orElseThrow().status())
                .isEqualTo(AttentionItem.RESOLVED);
        assertThat(projects.find(name)).isPresent();
    }

    // ───────────────────────────── plans, tasks, the scheduler ─────────────────────────────

    Project projectWithRepo() {
        RepoKey key = GITHUB.create("me/" + name);
        Project p = projects.create(name, null);
        projects.addGithubRepo(p.id(), key);
        return p;
    }

    Task task(Project p, String title) {
        return taskService.createGraph(List.of(new TaskDef("t-" + UUID.randomUUID(), title, "Do it.", null, null, null,
                p == null ? TaskPlacement.NONE : new TaskPlacement(p.id(), null))), List.of()).get(0);
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting until " + what);
            Thread.sleep(100);
        }
    }

    static JsonNode projectIn(JsonNode status, String projectName) {
        for (JsonNode p : status.path("report").path("projects")) {
            if (projectName.equals(p.path("name").asText())) return p;
        }
        throw new AssertionError("no project " + projectName + " in " + status);
    }

    @Test
    void planToolStartsAPlanAndStatusShowsIt() throws Exception {
        Project p = projectWithRepo();

        JsonNode started = call("orange_plan", args().put("project", name).put("request", "Build a TODO app. [[fake:plan]]"));

        assertThat(started.path("status").asText()).isEqualTo("planning");
        assertThat(started.path("hint").asText()).contains("orange_status");
        UUID planId = UUID.fromString(started.path("plan_id").asText());
        // No evidence in force: the plan asks first, so it stays in flight for the status to show.
        await("the plan asks", () -> Plan.ASKING.equals(plans.findById(planId).orElseThrow().status()));

        JsonNode status = call("orange_status", args());
        JsonNode mine = projectIn(status, name);
        assertThat(mine.path("plans")).hasSize(1);
        assertThat(mine.path("plans").get(0).path("id").asText()).isEqualTo(planId.toString());
        assertThat(mine.path("plans").get(0).path("status").asText()).isEqualTo("asking");
        assertThat(mine.path("open_attention").asInt()).isEqualTo(1);
        assertThat(status.path("text").asText()).contains(name).contains("asks you first");

        assertThat(refused("orange_plan", args().put("project", name).put("request", " "))).contains("request");
        assertThat(refused("orange_plan", args().put("project", "no-such-" + name).put("request", "x")))
                .contains("no such project");
        assertThat(p.id()).isNotNull();
    }

    @Test
    void taskToolAcceptsAPrefix() throws Exception {
        Project p = projectWithRepo();
        Task t = task(p, "Build the API");
        Run run = RunTestSupport.start(runs, runsProps, t.id(), RunKind.IMPLEMENT, "implementer", "{}");
        runs.finish(run.id(), RunStatus.FAILED, "no_outcome", new BigDecimal("0.42"), 10, 20, 0, null);
        evidence.record(t.id(), run.id(), "abc123", "tests", "sh test.sh", 0, "ok", true);
        for (int i = 0; i < 20; i++) {
            taskEvents.append(t.id(), "orange", "step", JSONB.valueOf("{\"n\":" + i + "}"));
        }

        for (String ref : List.of(t.id().toString(), t.shortId(), t.id().toString().substring(0, 24),
                t.id().toString().toUpperCase())) {
            JsonNode shown = call("orange_task", args().put("id", ref));
            assertThat(shown.path("task").path("id").asText()).as(ref).isEqualTo(t.id().toString());
        }
        JsonNode shown = call("orange_task", args().put("id", t.shortId()));
        assertThat(shown.path("task").path("short_id").asText()).isEqualTo(t.shortId());
        assertThat(shown.path("task").path("title").asText()).isEqualTo("Build the API");
        assertThat(shown.path("task").path("project").asText()).isEqualTo(name);
        assertThat(shown.path("runs")).hasSize(1);
        assertThat(shown.path("runs").get(0).path("kind").asText()).isEqualTo("implement");
        assertThat(shown.path("runs").get(0).path("status").asText()).isEqualTo("failed");
        assertThat(shown.path("runs").get(0).path("reason").asText()).isEqualTo("no_outcome");
        assertThat(shown.path("runs").get(0).path("cost_usd").decimalValue()).isEqualByComparingTo("0.42");
        assertThat(shown.path("events")).hasSize(15);
        assertThat(shown.path("events").get(14).path("payload").path("n").asInt()).as("oldest first, the latest last")
                .isEqualTo(19);
        assertThat(shown.path("evidence")).hasSize(1);
        assertThat(shown.path("evidence").get(0).path("check").asText()).isEqualTo("sh test.sh");
        assertThat(shown.path("evidence").get(0).path("passed").asBoolean()).isTrue();
        assertThat(shown.path("attention")).isEmpty();
    }

    @Test
    void anAmbiguousPrefixIsAnError() throws Exception {
        String prefix = UUID.randomUUID().toString().substring(0, 8);
        String suffix = UUID.randomUUID().toString().substring(28);
        for (int i = 0; i < 2; i++) {
            dsl.insertInto(TASKS).set(TASKS.ID, UUID.fromString(prefix + "-0000-7000-8000-" + i + UUID.randomUUID().toString().substring(25)))
                    .set(TASKS.TITLE, "twin " + i).set(TASKS.ROLE, "implementer").set(TASKS.STATUS, "ready").execute();
        }
        for (int i = 0; i < 2; i++) {
            dsl.insertInto(TASKS).set(TASKS.ID, UUID.fromString(UUID.randomUUID().toString().substring(0, 28) + suffix))
                    .set(TASKS.TITLE, "tail twin " + i).set(TASKS.ROLE, "implementer").set(TASKS.STATUS, "ready").execute();
        }

        assertThat(refused("orange_task", args().put("id", prefix))).contains("ambiguous id: 2 tasks").contains(prefix);
        assertThat(refused("orange_tail", args().put("id", suffix))).contains("ambiguous id: 2 tasks");
        assertThat(refused("orange_cancel", args().put("id", prefix))).contains("ambiguous id");
        assertThat(refused("orange_task", args().put("id", "abc"))).contains("at least 8");
        assertThat(refused("orange_task", args().put("id", "zzzzzzzz"))).contains("at least 8");
        assertThat(refused("orange_task", args().put("id", UUID.randomUUID().toString()))).contains("no task");
        assertThat(call("orange_task", args().put("id", prefix + "-0000-7000-8000-0")).path("task").path("title").asText())
                .isEqualTo("twin 0");
    }

    @Test
    void tailShowsTheLatestRunsEvents() throws Exception {
        Task t = task(null, "Tail me");
        Run old = RunTestSupport.start(runs, runsProps, t.id(), RunKind.IMPLEMENT, "implementer", "{}");
        runEvents.append(old.id(), 1, "assistant", JSONB.valueOf("{\"text\":\"from the old run\"}"));
        runs.finish(old.id(), RunStatus.FAILED, "no_outcome", null, 0, 0, 0, null);
        Run live = RunTestSupport.start(runs, runsProps, t.id(), RunKind.CI_FIX, "implementer", "{}");
        runEvents.append(live.id(), 1, "init", JSONB.valueOf("{\"session_id\":\"s\",\"model\":\"claude-test\"}"));
        runEvents.append(live.id(), 2, "assistant", JSONB.valueOf("{\"text\":\"Let me look at the tests.\\nThen fix.\"}"));
        runEvents.append(live.id(), 3, "tool_use",
                JSONB.valueOf("{\"id\":\"u1\",\"tool\":\"Bash\",\"input\":{\"command\":\"./gradlew test\"}}"));
        runEvents.append(live.id(), 4, "tool_result",
                JSONB.valueOf("{\"id\":\"u1\",\"is_error\":true,\"excerpt\":\"1 test failed\"}"));

        JsonNode tail = call("orange_tail", args().put("id", t.shortId()).put("limit", 3));

        assertThat(tail.path("run").path("id").asText()).isEqualTo(live.id().toString());
        assertThat(tail.path("run").path("kind").asText()).isEqualTo("ci_fix");
        assertThat(tail.path("run").path("status").asText()).isEqualTo("running");
        JsonNode events = tail.path("events");
        assertThat(events).hasSize(3);
        assertThat(events.get(0).path("type").asText()).isEqualTo("assistant");
        assertThat(events.get(0).path("text").asText()).isEqualTo("Let me look at the tests. Then fix.");
        assertThat(events.get(1).path("text").asText()).contains("Bash").contains("./gradlew test");
        assertThat(events.get(2).path("text").asText()).contains("error").contains("1 test failed");
        assertThat(tail.toString()).doesNotContain("from the old run");

        runs.finish(live.id(), RunStatus.SUCCEEDED, null, null, 0, 0, 0, null);
        assertThat(call("orange_tail", args().put("id", t.shortId())).path("run").path("id").asText())
                .as("no run running: the latest").isEqualTo(live.id().toString());
        assertThat(refused("orange_tail", args().put("id", task(null, "Never ran").shortId()))).contains("no run");
    }

    @Test
    void cancelPauseResume() throws Exception {
        Task t = task(null, "Cancel me");

        JsonNode cancelled = call("orange_cancel", args().put("id", t.shortId()));
        assertThat(cancelled.path("task").path("status").asText()).isEqualTo("cancelled");
        assertThat(cancelled.path("was").asText()).isEqualTo("ready");
        assertThat(call("orange_cancel", args().put("id", t.shortId())).path("task").path("status").asText())
                .as("cancelling again changes nothing").isEqualTo("cancelled");

        JsonNode paused = call("orange_pause", args().put("reason", "lunch"));
        assertThat(paused.path("paused").path("reason").asText()).isEqualTo("user: lunch");
        assertThat(paused.path("paused").path("until").isNull()).isTrue();
        assertThat(pause.current()).isPresent();
        assertThat(call("orange_status", args()).path("text").asText()).contains("user: lunch");
        assertThat(refused("orange_plan", args().put("project", projectWithRepo().name()).put("request", "x")))
                .contains("paused");

        JsonNode resumed = call("orange_resume", args());
        assertThat(resumed.path("paused").isNull()).isTrue();
        assertThat(pause.current()).isEmpty();
        assertThat(call("orange_pause", args()).path("paused").path("reason").asText()).isEqualTo("user");
    }

    @Test
    void projectCreateWithAnExistingRepoClonesAndActivates() throws Exception {
        RepoKey key = GITHUB.create("me/" + name);

        JsonNode created = call("orange_project_create", args().put("name", name).put("repo", "me/" + name)
                .put("summary", "A new thing"));

        assertThat(created.path("status").asText()).isEqualTo("active");
        assertThat(created.path("summary").asText()).isEqualTo("A new thing");
        assertThat(created.path("github_repos")).hasSize(1);
        assertThat(created.path("github_repos").get(0).path("key").asText()).isEqualTo(key.toString());
        assertThat(created.path("hint").asText()).contains("evidence");
        assertThat(Files.isDirectory(mirrors.path(key))).as("cloned").isTrue();
        Project p = projects.find(name).orElseThrow();
        assertThat(p.status()).isEqualTo(Project.ACTIVE);
        assertThat(attention.findByKey("project:" + p.id() + ":proposed")).isEmpty();
        assertThat(refused("orange_project_create", args().put("name", name))).contains("taken");
        assertThat(refused("orange_project_create", args().put("name", name + "-2").put("repo", "me/" + name)))
                .contains("already a repo of project " + name);
    }

    @Test
    void projectCreateWithAnInaccessibleRepoCreatesNothing() throws Exception {
        int before = dsl.fetchCount(PROJECTS);

        String why = refused("orange_project_create", args().put("name", name).put("repo", "me/missing-" + name));

        assertThat(why).contains("me/missing-" + name.toLowerCase());
        assertThat(why).doesNotContain(TOKEN);
        assertThat(projects.find(name)).isEmpty();
        assertThat(dsl.fetchCount(PROJECTS)).isEqualTo(before);
        assertThat(refused("orange_project_create", args().put("name", name).put("repo", "noslash")))
                .contains("owner/name");
    }

    @Test
    void projectCreateWithCreateTrueMakesTheRepo() throws Exception {
        API_CALLS.clear();

        JsonNode created = call("orange_project_create", args().put("name", name).put("repo", name + "-app")
                .put("create", true));

        RepoKey key = RepoKey.of("me/" + name + "-app");
        assertThat(created.path("github_repos").get(0).path("key").asText()).isEqualTo(key.toString());
        assertThat(API_CALLS).containsExactly("GET /user Bearer " + TOKEN, "POST /user/repos Bearer " + TOKEN);
        assertThat(GITHUB.head(key, "main")).as("GitHub has the repo with its first commit").isNotNull();
        assertThat(Files.isDirectory(mirrors.path(key))).isTrue();
        assertThat(projects.find(name).orElseThrow().status()).isEqualTo(Project.ACTIVE);
    }

    @Test
    void aCreatedRepoThatCannotBeClonedSaysItExistsNow() throws Exception {
        String why = refused("orange_project_create", args().put("name", name).put("repo", name + "-ghost")
                .put("create", true));

        assertThat(why).contains("me/" + name.toLowerCase() + "-ghost").contains("now exists on GitHub")
                .contains("without create");
        assertThat(why).doesNotContain(TOKEN);
        assertThat(projects.find(name)).isEmpty();
    }

    @Test
    void projectCreatePutsTheGivenEvidenceInForce() throws Exception {
        GITHUB.create("me/" + name);
        ObjectNode a = args().put("name", name).put("repo", "me/" + name);
        a.putArray("evidence").add("sh test.sh").add("sh lint.sh");

        JsonNode created = call("orange_project_create", a);

        Project p = projects.find(name).orElseThrow();
        JsonNode inForce = conventions.resolve("evidence", p.id(), null).orElseThrow();
        assertThat(inForce.get(0).asText()).isEqualTo("sh test.sh");
        assertThat(inForce.get(1).asText()).isEqualTo("sh lint.sh");
        assertThat(created.path("conventions").get(0).path("author").asText()).isEqualTo("user");
        assertThat(created.path("conventions").get(0).path("status").asText()).isEqualTo("active");
        assertThat(created.has("hint")).isFalse();

        ObjectNode bad = args().put("name", name + "-bad").put("repo", "me/" + name);
        bad.putArray("evidence").add(" ");
        assertThat(refused("orange_project_create", bad)).contains("evidence");
        assertThat(projects.find(name + "-bad")).isEmpty();
    }
}
