package com.ai.orange.session;

import static com.ai.orange.db.jooq.Tables.SESSIONS;
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
        String payload;
        try (var in = getClass().getResourceAsStream("/hooks/" + fixture + ".json")) {
            payload = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("SESSION", sessionId).replace("CWD", cwd.toString());
        }
        ProcessBuilder pb = new ProcessBuilder("python3", REPORTER.toString()).redirectErrorStream(true);
        pb.environment().put("ORANGE_URL", "http://localhost:" + port);
        pb.environment().put("ORANGE_NODE", "test-node");
        pb.environment().put("ORANGE_NODE_DIR", nodeDir.toString());
        // These tests apply events only after the hook has returned; a Stop hook must not wait for a verdict.
        pb.environment().put("ORANGE_HOOK_WAIT", "0");
        pb.environment().remove("ORANGE_API_TOKEN");
        Process p = pb.start();
        p.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(20, TimeUnit.SECONDS)).isTrue();
        // A hook's stdout can end up in the session's context, so the reporter says nothing, ever.
        assertThat(out).isEmpty();
        assertThat(p.exitValue()).isZero();
        processor.drain();
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

    HttpResponse<String> post(HttpClient http, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/sessions/events"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
