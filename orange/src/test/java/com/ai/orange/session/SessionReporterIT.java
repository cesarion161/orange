package com.ai.orange.session;

import static com.ai.orange.db.jooq.Tables.SESSIONS;
import static com.ai.orange.db.jooq.Tables.SESSION_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.events.EventProcessor;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectRepository;
import com.ai.orange.project.ProjectService;
import com.ai.orange.worktree.Git;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Every hook event, end to end: the plugin's real reporter script is fed a Claude Code hook
 * payload ({@code src/test/resources/hooks}), posts to orange, and the inbox applies it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SessionReporterIT {

    static final Path REPORTER = Path.of("..", "plugin", "hooks", "orange-report.py").toAbsolutePath().normalize();

    @Value("${local.server.port}") int port;
    @Autowired EventProcessor processor;
    @Autowired SessionService sessionService;
    @Autowired SessionRepository sessions;
    @Autowired AttentionRepository attention;
    @Autowired AttentionService attentionService;
    @Autowired ProjectRepository projects;
    @Autowired ProjectService projectService;
    @Autowired DSLContext dsl;

    @TempDir Path tmp;
    Path repo;
    Path nodeDir;
    String sessionId;

    @BeforeAll
    static void needsPython() throws Exception {
        boolean python;
        try {
            python = new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (java.io.IOException e) {
            python = false;
        }
        assumeTrue(python, "python3 is not installed");
        assertThat(REPORTER).exists();
    }

    @BeforeEach
    void repo() throws Exception {
        repo = Files.createDirectories(tmp.resolve("retry-" + UUID.randomUUID().toString().substring(0, 8))).toRealPath();
        Git.run(repo, Git.QUICK, "init", "-b", "feat/retry");
        Git.run(repo, Git.QUICK, "-c", "user.name=t", "-c", "user.email=t@example.com", "commit", "--allow-empty", "-m",
                "init");
        nodeDir = tmp.resolve("node");
        sessionId = "sess-" + UUID.randomUUID();
    }

    /** Runs the reporter the way Claude Code runs a hook, then lets the inbox apply what it posted. */
    void hook(String fixture, Path cwd) throws Exception {
        hook(fixture, cwd, "http://localhost:" + port);
    }

    /** An address where nothing listens: orange out of reach (the laptop offline, the VPS down). */
    static String unreachable() throws Exception {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return "http://127.0.0.1:" + s.getLocalPort();
        }
    }

    /** What is queued: the files in the reporter's queue directory, oldest first. */
    List<Path> queued() throws Exception {
        Path q = nodeDir.resolve("queue");
        if (!Files.isDirectory(q)) return List.of();
        try (var files = Files.list(q)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList();
        }
    }

    List<String> eventTypes() {
        return dsl.select(SESSION_EVENTS.TYPE).from(SESSION_EVENTS).where(SESSION_EVENTS.SESSION_ID.eq(session().id()))
                .orderBy(SESSION_EVENTS.ID).fetch(SESSION_EVENTS.TYPE);
    }

    void hook(String fixture, Path cwd, String url) throws Exception {
        // A hook's stdout can end up in the session's context, so the reporter says nothing, ever.
        assertThat(run(fixture, cwd, url, Map.of())).isEmpty();
    }

    /** Runs the reporter with these variables added, applies what it posted, and returns what it printed. */
    String run(String fixture, Path cwd, String url, Map<String, String> env) throws Exception {
        String payload;
        try (var in = getClass().getResourceAsStream("/hooks/" + fixture + ".json")) {
            payload = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("SESSION", sessionId).replace("CWD", cwd.toString());
        }
        ProcessBuilder pb = new ProcessBuilder("python3", REPORTER.toString()).redirectErrorStream(true);
        pb.environment().put("ORANGE_URL", url);
        pb.environment().put("ORANGE_NODE", "test-node");
        pb.environment().put("ORANGE_NODE_DIR", nodeDir.toString());
        // These tests apply events only after the hook has returned; a Stop hook must not wait for a verdict.
        pb.environment().put("ORANGE_HOOK_WAIT", "0");
        pb.environment().remove("ORANGE_API_TOKEN");
        // Not a steering session unless a test says so, whatever the machine running the suite has.
        pb.environment().remove("ORANGE_STEERING");
        pb.environment().put("ORANGE_STEERING_DIR", tmp.resolve("steering-unused").toString());
        pb.environment().putAll(env);
        Process p = pb.start();
        p.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(20, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).isZero();
        processor.drain();
        return out;
    }

    void hook(String fixture) throws Exception {
        hook(fixture, repo);
    }

    Session session() {
        return sessions.find("claude-code", sessionId).orElseThrow();
    }

    List<AttentionItem> items() {
        return attention.findBySession(session().id());
    }

    void waitedFor(int minutes) {
        dsl.update(SESSIONS).set(SESSIONS.WAITING_SINCE, OffsetDateTime.now().minusMinutes(minutes))
                .where(SESSIONS.ID.eq(session().id())).execute();
    }

    @Test
    void aSessionInAnUnknownRepoProposesAProject() throws Exception {
        hook("session-start");

        Session s = session();
        assertThat(s.status()).isEqualTo(Session.ACTIVE);
        assertThat(s.nodeId()).isEqualTo("test-node");
        assertThat(s.cwd()).isEqualTo(repo.toString());
        assertThat(s.repoRoot()).isEqualTo(repo.toString());
        assertThat(s.branch()).isEqualTo("feat/retry");
        Project project = projects.findById(s.projectId()).orElseThrow();
        assertThat(project.status()).isEqualTo(Project.PROPOSED);
        assertThat(project.name()).isEqualTo(repo.getFileName().toString());
        assertThat(projects.repos(project.id())).singleElement()
                .satisfies(r -> assertThat(r.repoRoot()).isEqualTo(repo.toString()));

        AttentionItem proposed = attention.findByKey("project:" + project.id() + ":proposed").orElseThrow();
        assertThat(proposed.kind()).isEqualTo(AttentionItem.PROJECT_PROPOSED);
        assertThat(proposed.urgency()).isEqualTo(AttentionItem.NORMAL);

        // The same repo again proposes nothing new, and "confirm" activates the project.
        hook("user-prompt-submit");
        assertThat(projects.findRepo("test-node", repo.toString())).isPresent();
        assertThat(session().projectId()).isEqualTo(project.id());
        attentionService.resolve(proposed.id(), "confirm", "user");
        assertThat(projects.findById(project.id()).orElseThrow().status()).isEqualTo(Project.ACTIVE);
    }

    @Test
    void aSessionInAWorktreeBelongsToTheMainCheckoutsProject() throws Exception {
        Path worktree = tmp.resolve("wt-" + UUID.randomUUID().toString().substring(0, 8));
        Git.run(repo, Git.QUICK, "worktree", "add", "-b", "feat/side", worktree.toString());
        hook("session-start", worktree.toRealPath());

        Session s = session();
        assertThat(s.repoRoot()).isEqualTo(repo.toString());
        assertThat(s.worktree()).isEqualTo(worktree.toRealPath().toString());
        assertThat(s.branch()).isEqualTo("feat/side");
        assertThat(projects.findRepo("test-node", repo.toString()).orElseThrow().projectId()).isEqualTo(s.projectId());
    }

    @Test
    void aQuestionIsFiledThenUrgentThenResolvedByTheUsersPrompt() throws Exception {
        hook("session-start");
        hook("user-prompt-submit");
        assertThat(session().intent()).isEqualTo("Add retry with backoff to the gateway client");
        assertThat(session().lastUserPromptAt()).isNotNull();

        hook("stop-question");
        Session stopped = session();
        assertThat(stopped.status()).isEqualTo(Session.STOPPED);
        assertThat(stopped.waitingReason()).isEqualTo(Session.WAIT_QUESTION);
        assertThat(stopped.lastMessage()).endsWith("Should I proceed with option 1?");
        AttentionItem item = items().getFirst();
        assertThat(items()).hasSize(1);
        assertThat(item.kind()).isEqualTo(AttentionItem.SESSION_QUESTION);
        assertThat(item.urgency()).isEqualTo(AttentionItem.NORMAL);
        assertThat(item.title()).contains("feat/retry").contains("Should I proceed with option 1?");
        assertThat(item.body()).contains("Doing: Add retry with backoff").contains(repo.toString());

        // Not long enough yet.
        sessionService.sweep();
        assertThat(items().getFirst().urgency()).isEqualTo(AttentionItem.NORMAL);
        waitedFor(16);
        sessionService.sweep();
        assertThat(items().getFirst().urgency()).isEqualTo(AttentionItem.URGENT);

        hook("user-prompt-submit");
        assertThat(session().status()).isEqualTo(Session.ACTIVE);
        assertThat(session().waitingSince()).isNull();
        AttentionItem resolved = items().getFirst();
        assertThat(resolved.status()).isEqualTo(AttentionItem.RESOLVED);
        assertThat(resolved.resolvedBy()).isEqualTo("user_prompt");
        // The first prompt stays the intent.
        assertThat(session().intent()).isEqualTo("Add retry with backoff to the gateway client");
    }

    @Test
    void aStopCarriesTheHeadAndTheUncommittedFiles() throws Exception {
        hook("user-prompt-submit");
        hook("stop-finished");
        String head = Git.head(repo);
        assertThat(session().headSha()).isEqualTo(head);
        assertThat(session().dirtyFiles()).as("a clean checkout says so").isEmpty();

        Files.writeString(repo.resolve("new.txt"), "x\n");
        hook("user-prompt-submit");
        assertThat(session().headSha()).as("other events keep what the last stop said").isEqualTo(head);
        hook("stop-finished");
        assertThat(session().dirtyFiles()).containsExactly("?? new.txt");
    }

    @Test
    void aStopWithoutTheFactsForgetsTheOldOnes() throws Exception {
        hook("user-prompt-submit");
        hook("stop-finished");
        assertThat(session().headSha()).isNotNull();

        // A stop from an older reporter, or one that could not read git: nothing stale is kept.
        sessionService.apply(com.ai.orange.session.NeutralEvent.parse(com.ai.orange.json.Json.mapper().readTree(
                "{\"event_id\":\"" + UUID.randomUUID() + "\",\"harness\":\"claude-code\",\"session_id\":\""
                        + sessionId + "\",\"type\":\"stopped\",\"node\":\"test-node\",\"payload\":{}}")));
        assertThat(session().headSha()).isNull();
        assertThat(session().dirtyFiles()).isNull();
    }

    @Test
    void aFinishedSessionIsFiledOnceAfterItSatIdle() throws Exception {
        hook("user-prompt-submit");
        hook("stop-finished");
        assertThat(session().waitingReason()).isEqualTo(Session.WAIT_FINISHED);
        hook("notification-idle");
        sessionService.sweep();
        assertThat(items()).isEmpty();

        waitedFor(16);
        sessionService.sweep();
        sessionService.sweep();
        assertThat(items()).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(AttentionItem.SESSION_IDLE);
            assertThat(i.urgency()).isEqualTo(AttentionItem.NORMAL);
            assertThat(i.title()).contains("finished and is idle");
        });

        // Once the user has dealt with it, the same wait is not filed again.
        attentionService.resolve(items().getFirst().id(), "seen", "user");
        sessionService.sweep();
        assertThat(items()).hasSize(1);
    }

    @Test
    void aPermissionPromptWaitsUntilAToolRunsAgain() throws Exception {
        hook("user-prompt-submit");
        long before = sessions.countEvents(session().id());
        hook("post-tool-use");
        // No wait, no report: the PostToolUse hook is silent while a session just works.
        assertThat(sessions.countEvents(session().id())).isEqualTo(before);

        hook("notification-permission");
        assertThat(session().status()).isEqualTo(Session.WAITING_ON_USER);
        assertThat(session().waitingReason()).isEqualTo(Session.WAIT_PERMISSION);
        assertThat(items()).singleElement().satisfies(i -> {
            assertThat(i.kind()).isEqualTo(AttentionItem.SESSION_WAITING);
            assertThat(i.title()).contains("Claude needs your permission to use Bash");
        });
        assertThat(nodeDir.resolve("waiting").resolve(sessionId)).exists();

        hook("post-tool-use");
        assertThat(session().status()).isEqualTo(Session.ACTIVE);
        assertThat(session().waitingSince()).isNull();
        assertThat(items().getFirst().status()).isEqualTo(AttentionItem.RESOLVED);
        assertThat(items().getFirst().resolvedBy()).isEqualTo("resumed");
        assertThat(nodeDir.resolve("waiting").resolve(sessionId)).doesNotExist();

        // An unanswered prompt becomes urgent.
        hook("notification-permission");
        waitedFor(16);
        sessionService.sweep();
        assertThat(items()).filteredOn(i -> AttentionItem.OPEN.equals(i.status())).singleElement()
                .satisfies(i -> assertThat(i.urgency()).isEqualTo(AttentionItem.URGENT));
    }

    @Test
    void theEndOfASessionResolvesItsItems() throws Exception {
        hook("user-prompt-submit");
        hook("stop-question");
        hook("session-end");

        assertThat(session().status()).isEqualTo(Session.ENDED);
        assertThat(session().closedReason()).isEqualTo("prompt_input_exit");
        assertThat(items()).singleElement().satisfies(i -> assertThat(i.resolvedBy()).isEqualTo("session_ended"));
        sessionService.sweep();
        assertThat(items()).hasSize(1);
    }

    @Test
    void anArchivedProjectsSessionsRaiseNothing() throws Exception {
        hook("session-start");
        projectService.update(session().projectId(), null, Project.ARCHIVED, null);

        hook("stop-question");
        assertThat(session().status()).isEqualTo(Session.STOPPED);
        waitedFor(60);
        sessionService.sweep();
        assertThat(items()).isEmpty();
    }

    @Test
    void anEventIsStoredOnceAndABadOneRefused() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        String id = UUID.randomUUID().toString();
        String event = "{\"event_id\":\"" + id + "\",\"harness\":\"claude-code\",\"session_id\":\"" + sessionId
                + "\",\"type\":\"user_prompt\",\"payload\":{\"prompt\":\"hello\"}}";
        assertThat(post(http, event).body()).contains("\"stored\":true");
        assertThat(post(http, event).body()).contains("\"stored\":false");
        processor.drain();
        assertThat(sessions.countEvents(session().id())).isEqualTo(1);
        // No repo: the session has no project and still counts.
        assertThat(session().projectId()).isNull();
        assertThat(session().intent()).isEqualTo("hello");

        assertThat(post(http, event.replace("user_prompt", "exploded")).statusCode()).isEqualTo(400);
        assertThat(post(http, event.replace("claude-code", "vim")).statusCode()).isEqualTo(400);
        assertThat(post(http, "{\"harness\":\"claude-code\",\"session_id\":\"x\",\"type\":\"stopped\"}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void orangeOutOfReachQueuesTheEventAndReturnsAtOnce() throws Exception {
        long start = System.nanoTime();
        hook("user-prompt-submit", repo, unreachable());
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(4));
        assertThat(queued()).singleElement().satisfies(f -> assertThat(Files.readString(f)).contains("\"user_prompt\""));
        assertThat(sessions.find("claude-code", sessionId)).isEmpty();
    }

    @Test
    void theNextHookReplaysTheQueueFirstInOrder() throws Exception {
        String down = unreachable();
        hook("session-start", repo, down);
        hook("user-prompt-submit", repo, down);
        assertThat(queued()).hasSize(2);

        hook("stop-finished");
        assertThat(queued()).isEmpty();
        assertThat(eventTypes()).containsExactly("session_started", "user_prompt", "stopped");
        // What waited says so, and orange judges none of it; the hook's own event is as fresh as ever.
        assertThat(dsl.select(SESSION_EVENTS.PAYLOAD).from(SESSION_EVENTS).where(SESSION_EVENTS.SESSION_ID.eq(session().id()))
                .orderBy(SESSION_EVENTS.ID).fetch(r -> r.value1().data()))
                .satisfiesExactly(
                        first -> assertThat(first).contains("\"replayed\": true"),
                        second -> assertThat(second).contains("\"replayed\": true"),
                        own -> assertThat(own).doesNotContain("replayed"));
    }

    @Test
    void aReplayIsIdempotent() throws Exception {
        hook("user-prompt-submit", repo, unreachable());
        Path file = queued().getFirst();
        String copy = Files.readString(file);
        hook("post-tool-use");
        // The same event once more, as if a replay had been cut off after orange took it.
        Files.writeString(file, copy);
        hook("post-tool-use");
        assertThat(eventTypes().stream().filter("user_prompt"::equals)).hasSize(1);
        assertThat(queued()).isEmpty();
    }

    @Test
    void theQueueIsBounded() throws Exception {
        Path q = Files.createDirectories(nodeDir.resolve("queue"));
        long base = System.currentTimeMillis() * 1_000_000L;
        for (int i = 0; i < 1005; i++) {
            Files.writeString(q.resolve(String.format("%d-%s.json", base + i, UUID.randomUUID())), "{}");
        }
        Path old = q.resolve(String.format("%d-%s.json", base - 1, UUID.randomUUID()));
        Files.writeString(old, "{}");
        Files.setLastModifiedTime(old, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minus(java.time.Duration.ofDays(8))));

        hook("user-prompt-submit", repo, unreachable());
        assertThat(queued()).hasSize(1000).doesNotContain(old);
        // The newest stay: the hook's own event among them.
        assertThat(Files.readString(queued().getLast())).contains("\"user_prompt\"");
    }

    @Test
    void anEventOrangeRefusesIsDroppedNotRetried() throws Exception {
        Path q = Files.createDirectories(nodeDir.resolve("queue"));
        // No type: orange answers 400, and no replay will ever change that.
        Files.writeString(q.resolve("1-bad.json"), "{\"event_id\":\"" + UUID.randomUUID() + "\",\"harness\":\"claude-code\","
                + "\"session_id\":\"" + sessionId + "\"}");
        hook("user-prompt-submit");
        assertThat(queued()).isEmpty();
        assertThat(eventTypes()).containsExactly("user_prompt");
    }

    @Test
    void theTokenNeverFollowsARedirect() throws Exception {
        // Something at ORANGE_URL that sends every request elsewhere: the token must not go along.
        List<String> landed = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> asked = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            if (ex.getRequestURI().getPath().startsWith("/elsewhere")) {
                landed.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " "
                        + ex.getRequestHeaders().getFirst("Authorization"));
                byte[] body = "{\"state\":\"none\"}".getBytes(StandardCharsets.UTF_8);
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
            } else {
                asked.add(ex.getRequestHeaders().getFirst("Authorization"));
                ex.getResponseHeaders().add("Location", base + "/elsewhere" + ex.getRequestURI().getPath());
                ex.sendResponseHeaders(302, -1);
            }
            ex.close();
        });
        server.start();
        try {
            Map<String, String> env = Map.of("ORANGE_API_TOKEN", "secret-test-token", "ORANGE_STEERING", "1",
                    "ORANGE_HOOK_WAIT", "2");
            run("session-start", repo, base, env);
            run("stop-finished", repo, base, env);
        } finally {
            server.stop(0);
        }

        // orange itself is asked with the token; the event post, the steering context and the verdict
        // poll were each sent on, without it.
        assertThat(asked).hasSizeGreaterThanOrEqualTo(3).allSatisfy(a -> assertThat(a).isEqualTo("Bearer secret-test-token"));
        assertThat(landed).anySatisfy(l -> assertThat(l).startsWith("GET /elsewhere/sessions/events"))
                .anySatisfy(l -> assertThat(l).startsWith("GET /elsewhere/steering/context"))
                .anySatisfy(l -> assertThat(l).startsWith("GET /elsewhere/sessions/verdict"))
                .allSatisfy(l -> assertThat(l).endsWith(" null").doesNotContain("secret-test-token"));
    }

    /** What orange answers at {@code GET /steering/context} right now. */
    String steeringContext() throws Exception {
        HttpResponse<String> res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/steering/context")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(200);
        return res.body();
    }

    /** The one thing a steering start prints: the SessionStart hook's added context. */
    static String addedContext(String out) throws Exception {
        var json = com.ai.orange.json.Json.mapper().readTree(out);
        assertThat(json.size()).isEqualTo(1);
        assertThat(json.path("hookSpecificOutput").path("hookEventName").asText()).isEqualTo("SessionStart");
        assertThat(json.path("hookSpecificOutput").size()).isEqualTo(2);
        return json.path("hookSpecificOutput").path("additionalContext").asText();
    }

    @Test
    void steeringStartPrintsOrangesContext() throws Exception {
        String out = run("session-start", repo, "http://localhost:" + port, Map.of("ORANGE_STEERING", "1"));

        assertThat(out.strip().lines()).as("one line of JSON and nothing else").hasSize(1);
        String context = addedContext(out);
        assertThat(context).isNotBlank().isEqualTo(steeringContext());
        assertThat(session().steering()).isTrue();
        String payload = dsl.select(SESSION_EVENTS.PAYLOAD).from(SESSION_EVENTS)
                .where(SESSION_EVENTS.SESSION_ID.eq(session().id())).fetchOne(r -> r.value1().data());
        assertThat(payload).contains("\"steering\": true");

        // Only a start adds context: the tab's other hooks print nothing, and the session stays steering.
        assertThat(run("user-prompt-submit", repo, "http://localhost:" + port, Map.of("ORANGE_STEERING", "1"))).isEmpty();
        assertThat(session().steering()).isTrue();
    }

    @Test
    void steeringStartWithOrangeDownPrintsNothing() throws Exception {
        long start = System.nanoTime();
        String out = run("session-start", repo, unreachable(), Map.of("ORANGE_STEERING", "1"));

        assertThat(java.time.Duration.ofNanos(System.nanoTime() - start)).isLessThan(java.time.Duration.ofSeconds(4));
        assertThat(out).isEmpty();
        // The start waits on disk as any event does, and says it is the steering tab's.
        assertThat(queued()).singleElement().satisfies(f -> assertThat(Files.readString(f))
                .contains("\"session_started\"").contains("\"steering\": true"));
    }

    @Test
    void aSessionUnderTheSteeringDirIsSteering() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("steering-real").resolve("today")).toRealPath();
        // The variable names the directory through a link; the session's cwd is the real path below it.
        Path link = Files.createSymbolicLink(tmp.resolve("steering-link"), dir.getParent());
        String out = run("session-start", dir, "http://localhost:" + port, Map.of("ORANGE_STEERING_DIR", link.toString()));
        assertThat(addedContext(out)).isNotBlank();
        assertThat(session().steering()).isTrue();

        // A sibling whose name merely starts the same is not under it.
        Path sibling = Files.createDirectories(tmp.resolve("steering-realness")).toRealPath();
        sessionId = "sess-" + UUID.randomUUID();
        assertThat(run("session-start", sibling, "http://localhost:" + port,
                Map.of("ORANGE_STEERING_DIR", dir.getParent().toString()))).isEmpty();
        assertThat(session().steering()).isFalse();
    }

    @Test
    void aNormalSessionPrintsNothingAtStart() throws Exception {
        assertThat(run("session-start", repo, "http://localhost:" + port, Map.of("ORANGE_STEERING", "0"))).isEmpty();
        assertThat(session().steering()).isFalse();
        assertThat(session().projectId()).isNotNull();
    }

    @Test
    void aSteeringSessionInARepoProposesNoProject() throws Exception {
        Map<String, String> steering = Map.of("ORANGE_STEERING", "1");
        run("session-start", repo, "http://localhost:" + port, steering);
        run("user-prompt-submit", repo, "http://localhost:" + port, steering);

        Session s = session();
        assertThat(s.steering()).isTrue();
        assertThat(s.projectId()).isNull();
        assertThat(projects.findRepo("test-node", repo.toString())).as("the repo is not taken for a project").isEmpty();
        assertThat(attention.findByKey("project:" + repo + ":proposed")).isEmpty();
        assertThat(items()).isEmpty();
    }

    @Test
    void aSteeringQuestionFilesNothing() throws Exception {
        Map<String, String> steering = Map.of("ORANGE_STEERING", "1");
        run("user-prompt-submit", repo, "http://localhost:" + port, steering);
        run("stop-question", repo, "http://localhost:" + port, steering);
        // A later event that does not say so (an older reporter, a replay) keeps the session steering.
        hook("notification-permission");

        assertThat(session().steering()).isTrue();
        assertThat(session().status()).isEqualTo(Session.WAITING_ON_USER);
        waitedFor(60);
        sessionService.sweep();
        assertThat(items()).isEmpty();

        hook("user-prompt-submit");
        hook("stop-finished");
        waitedFor(60);
        sessionService.sweep();
        assertThat(items()).as("not even a finished, idle tab").isEmpty();
    }

    HttpResponse<String> post(HttpClient http, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sessions/events"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
