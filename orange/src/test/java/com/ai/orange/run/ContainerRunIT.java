package com.ai.orange.run;

import static com.ai.orange.harness.container.ContainerTestSupport.containersOf;
import static com.ai.orange.run.RunTestSupport.git;
import static com.ai.orange.run.RunTestSupport.headInput;
import static com.ai.orange.run.RunTestSupport.runEventsText;
import static com.ai.orange.run.RunTestSupport.runningTask;
import static com.ai.orange.run.RunTestSupport.start;
import static com.ai.orange.run.RunTestSupport.verifyingTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.harness.container.ContainerTestSupport;
import com.ai.orange.json.Json;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A task's agent runs in container mode: the fake Claude Code runs in a container from the test
 * image, on a clone of the task's branch, and its commits come home as a bundle. Real Docker, no
 * model.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ContainerRunIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("container-run-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("container-run-it-wt-");
    /** A file of the host, in a directory no run is given. */
    static final Path CANARY = canary();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
        ContainerTestSupport.register(r);
        r.add("orange.runs.timeouts.ci_fix", () -> "6s");
    }

    @LocalServerPort int port;
    @Autowired WorkerRunner runner;
    @Autowired RunWorkspaces workspaces;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired RunEventRepository runEvents;
    @Autowired EvidenceRepository evidence;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired WorktreeService worktrees;
    @Autowired SchedulerPause pause;

    @BeforeEach
    void unpause() {
        pause.resume();
    }

    RunResult implement(Task task) {
        return runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, task.role(), "{}"));
    }

    @Test
    void anImplementRunWorksOnACloneAndItsCommitComesHome() throws Exception {
        Task task = runningTask(taskService, tasks, "Print hello.");
        String base = git(BASE_REPO, "rev-parse", "main");

        RunResult result = implement(task);

        assertThat(result.status()).as(String.valueOf(result.reason()) + "\n" + runFile(result, "../stderr.log"))
                .isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outcome()).isEqualTo(new RunOutcome.Result("done", "Implemented: Add a greeting", null));
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.VERIFYING);

        // The task's worktree, which only orange writes, is at the agent's commit.
        Path wt = worktrees.resolve(task.id());
        assertThat(git(wt, "log", "--format=%s|%an", "-1")).isEqualTo("fake: Add a greeting|orange[bot]");
        assertThat(wt.resolve("orange-fake.txt")).exists();
        assertThat(git(wt, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("orange/" + task.id());
        assertThat(git(wt, "rev-parse", "HEAD~1")).isEqualTo(base);
        assertThat(git(wt, "status", "--porcelain")).isEmpty();
        assertThat(after.headSha()).isEqualTo(git(wt, "rev-parse", "HEAD"));

        // Nothing of the run is left: no clone, no bundle, no container, no token file.
        Path runDir = RUN_ROOT.resolve(result.runId().toString());
        assertThat(runDir.resolve("workspace")).doesNotExist();
        assertThat(runDir.resolve("export")).doesNotExist();
        assertThat(runDir.resolve("mcp.json")).doesNotExist();
        assertThat(containersOf(result.runId())).isEmpty();

        // The container is the boundary, so the agent's Bash asks nobody.
        assertThat(runFile(result, "fake-args.json")).contains("\"--permission-mode\",\"bypassPermissions\"")
                .contains("\"--mcp-config\",\"" + runDir.resolve("mcp.json") + "\"");
        // It worked in the clone, not in the worktree.
        JsonNode init = Json.mapper().readTree(Files.readAllLines(runDir.resolve("stream.jsonl")).getFirst());
        assertThat(init.path("cwd").asText()).isEqualTo(runDir.resolve("workspace").toString());
        // From inside the container the coordinator is not "localhost", and it answered there.
        List<String> mcp = runFile(result, "fake-mcp.log").lines().toList();
        assertThat(mcp).contains("POST http://host.docker.internal:" + port + "/mcp/worker");
        assertThat(mcp).anyMatch(l -> l.startsWith("200 "));
        assertThat(mcp).allMatch(l -> l.startsWith("POST ") || l.startsWith("200 ") || l.startsWith("202 "));

        assertThat(runFile(result, "fake-env.json")).contains("\"CLAUDE_CODE_OAUTH_TOKEN\":\"test-plan-token\"");
        assertThat(runEventsText(runEvents, result.runId())).doesNotContain("test-plan-token");
        assertThat(pause.current()).isEmpty();
    }

    @Test
    void theAgentSeesItsCloneAndNothingElseOfTheHost() throws Exception {
        Task task = runningTask(taskService, tasks, "[[fake:probe]]");
        List<String> hostOnly = List.of(CANARY.toString(), BASE_REPO.toString(),
                worktrees.resolve(task.id()).toString(), WORKTREES.toString(), System.getProperty("user.home"),
                "/var/run/docker.sock");
        List<String> probes = new ArrayList<>(hostOnly);
        probes.add(".");
        StringBuilder detail = new StringBuilder();
        probes.forEach(p -> detail.append("probe: ").append(p).append('\n'));
        ObjectNode input = Json.mapper().createObjectNode();
        input.putArray("feedback").addObject().put("source", "test").put("summary", "Say what you can see.")
                .put("detail", detail.toString());
        assertThat(CANARY).as("the canary is there for whoever runs on the host").exists();
        assertThat(worktrees.ensure(task.id())).exists();

        RunResult result = runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, task.role(), input.toString()));

        assertThat(result.status()).as(String.valueOf(result.reason())).isEqualTo(RunStatus.SUCCEEDED);
        List<String> seen = runFile(result, "fake-probe.txt").lines().toList();
        List<String> expected = new ArrayList<>();
        hostOnly.forEach(p -> expected.add(p + "=hidden"));
        expected.add(".=visible");
        assertThat(seen).containsExactlyElementsOf(expected);

        // Nothing of orange's environment: no variable of this JVM arrives with its value.
        JsonNode env = Json.mapper().readTree(runFile(result, "fake-env.json"));
        // The image sets its own locale, which may be the host's too.
        Set<String> imagesOwn = Set.of("LANG", "LC_ALL", "LANGUAGE");
        List<String> inherited = new ArrayList<>();
        for (Map.Entry<String, String> host : System.getenv().entrySet()) {
            if (imagesOwn.contains(host.getKey())) continue;
            // Names only: a value here may be a credential of whoever runs the tests.
            if (env.has(host.getKey()) && env.get(host.getKey()).asText().equals(host.getValue())) inherited.add(host.getKey());
        }
        assertThat(inherited).as("host variables that reached the container with their value").isEmpty();
        assertThat(env.path("HOME").asText()).isEqualTo("/home/agent");
        assertThat(env.has("DOCKER_HOST")).isFalse();
        assertThat(env.has("USER")).isFalse();
        assertThat(env.has("TMPDIR")).isFalse();
        assertThat(containersOf(result.runId())).isEmpty();
    }

    @Test
    void aRunThatHangsIsKilledAtItsTimeoutWithItsContainer() {
        Task task = runningTask(taskService, tasks, "[[fake:hang]]");
        long started = System.nanoTime();

        RunResult result = runner.execute(start(runs, props, task.id(), RunKind.CI_FIX, "ci_fixer", "{}"));

        assertThat(result.reason()).isEqualTo("timeout");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(60));
        assertThat(containersOf(result.runId())).isEmpty();
        Path runDir = RUN_ROOT.resolve(result.runId().toString());
        assertThat(runDir.resolve("workspace")).doesNotExist();
        assertThat(runDir.resolve("export")).doesNotExist();
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isEqualTo(1);
    }

    @Test
    void doneWithoutACommitIsNoCommits() {
        Task task = runningTask(taskService, tasks, "[[fake:no-commit]]");
        String before = git(BASE_REPO, "rev-parse", "main");

        RunResult result = implement(task);

        // The export said there was nothing new, and the worktree is where it was.
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.reason()).isEqualTo("no_commits");
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(after.failures()).isEqualTo(1);
        assertThat(after.headSha()).isNull();
        assertThat(git(worktrees.resolve(task.id()), "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(containersOf(result.runId())).isEmpty();
    }

    @Test
    void theNextRunStartsFromWhatTheLastOneCommitted() throws Exception {
        Task task = runningTask(taskService, tasks, "Print hello.");
        // It commits and then reports that it failed: the commit comes home all the same.
        RunResult first = runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer",
                "{\"feedback\":[{\"source\":\"test\",\"summary\":\"[[fake:report-failed]]\"}]}"));
        assertThat(first.reason()).isEqualTo("reported_failed");
        Path wt = worktrees.resolve(task.id());
        assertThat(Files.readAllLines(wt.resolve("orange-fake.txt"))).hasSize(1);
        String afterFirst = git(wt, "rev-parse", "HEAD");

        RunResult second = implement(task);

        assertThat(second.status()).as(String.valueOf(second.reason())).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(Files.readAllLines(wt.resolve("orange-fake.txt"))).as("one line per run").hasSize(2);
        assertThat(git(wt, "rev-list", "--count", "main..HEAD")).as("both runs' commits are on the branch").isEqualTo("2");
        assertThat(git(wt, "rev-parse", "HEAD~1")).isEqualTo(afterFirst);
        assertThat(git(wt, "status", "--porcelain")).isEmpty();
        assertThat(runFile(second, "fake-prompt.txt")).contains("## Previous attempts").contains("reported_failed");
    }

    @Test
    void aDiffReviewReadsTheWorktreeAndCannotWriteIt() throws Exception {
        Task task = verifyingTask(taskService, tasks, worktrees, "Print hello.", "orange-fake.txt", "hello from the task\n");
        Path wt = worktrees.resolve(task.id());

        RunResult result = runner.execute(start(runs, props, task.id(), RunKind.DIFF_REVIEW, "reviewer",
                headInput(task.headSha())));

        assertThat(result.status()).as(String.valueOf(result.reason())).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(evidence.findByTaskAndHead(task.id(), task.headSha())).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("diff_review");
            assertThat(e.passed()).isTrue();
            assertThat(e.runId()).isEqualTo(result.runId());
        });
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.VERIFIED);

        // It ran in the worktree itself, mounted read-only, and a reviewer still asks before anything else.
        Path runDir = RUN_ROOT.resolve(result.runId().toString());
        JsonNode init = Json.mapper().readTree(Files.readAllLines(runDir.resolve("stream.jsonl")).getFirst());
        assertThat(init.path("cwd").asText()).isEqualTo(wt.toString());
        assertThat(runFile(result, "fake-args.json")).contains("\"--permission-mode\",\"dontAsk\"");
        assertThat(runFile(result, "fake-env.json")).contains("\"HOME\":\"/home/agent\"");
        assertThat(git(wt, "status", "--porcelain")).isEmpty();
        assertThat(git(wt, "rev-parse", "HEAD")).isEqualTo(task.headSha());
        assertThat(runDir.resolve("workspace")).as("a review gets no clone").doesNotExist();
        assertThat(containersOf(result.runId())).isEmpty();
    }

    @Test
    void theCloneHoldsTheTasksBranchAndTheBaseAndSharesNothing() throws Exception {
        Task task = verifyingTask(taskService, tasks, worktrees, "Print hello.", "mine.txt", "mine\n");
        Task other = verifyingTask(taskService, tasks, worktrees, "Print bye.", "theirs.txt", "theirs\n");
        Path runDir = RUN_ROOT.resolve(UUID.randomUUID().toString());

        Path clone = workspaces.cloneOf(task.id(), runDir);

        assertThat(clone).isEqualTo(runDir.resolve("workspace"));
        assertThat(git(clone, "rev-parse", "HEAD")).isEqualTo(task.headSha());
        assertThat(git(clone, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("orange/" + task.id());
        assertThat(git(clone, "for-each-ref", "--format=%(refname)").lines())
                .containsExactlyInAnyOrder("refs/heads/main", "refs/heads/orange/" + task.id());
        // The base under the name the prompt uses, so a fix run can rebase onto it.
        assertThat(git(clone, "rev-parse", worktrees.baseRef())).isEqualTo(git(BASE_REPO, "rev-parse", "main"));
        // No way back into orange's repository: no remote, no borrowed objects, no other task's work.
        assertThat(git(clone, "remote")).isEmpty();
        assertThat(clone.resolve(".git/objects/info/alternates")).doesNotExist();
        assertThat(Files.readString(clone.resolve(".git/config"))).doesNotContain(BASE_REPO.toString());
        assertThatThrownBy(() -> git(clone, "cat-file", "-e", other.headSha() + "^{commit}"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(clone.resolve("mine.txt")).exists();

        workspaces.discard(runDir);
        assertThat(clone).doesNotExist();
    }

    @Test
    void aHeadBehindItsStartBringsNothingHome() throws Exception {
        Task task = verifyingTask(taskService, tasks, worktrees, "Print hello.", "mine.txt", "mine\n");
        Path wt = worktrees.resolve(task.id());
        Run run = start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer", "{}");
        Path runDir = RUN_ROOT.resolve(run.id().toString());
        try {
            Path clone = workspaces.cloneOf(task.id(), runDir);
            // What an agent that threw the task's commits away would leave.
            git(clone, "reset", "-q", "--hard", "main");

            assertThat(workspaces.bringHome(run, clone, runDir, task.headSha())).isEqualTo(RunWorkspaces.Home.UNREADABLE);
            assertThat(git(wt, "rev-parse", "HEAD")).as("the task's branch keeps its commits").isEqualTo(task.headSha());

            // And a worktree that moved while the run worked is not overwritten.
            git(clone, "reset", "-q", "--hard", task.headSha());
            Files.writeString(clone.resolve("more.txt"), "more\n");
            git(clone, "add", "-A");
            git(clone, "-c", "user.email=t@e", "-c", "user.name=T", "commit", "-q", "-m", "more");
            assertThat(workspaces.bringHome(run, clone, runDir, git(BASE_REPO, "rev-parse", "main")))
                    .isEqualTo(RunWorkspaces.Home.UNREADABLE);
            assertThat(git(wt, "rev-parse", "HEAD")).isEqualTo(task.headSha());

            // The same clone with the right start comes home.
            assertThat(workspaces.bringHome(run, clone, runDir, task.headSha())).isEqualTo(RunWorkspaces.Home.IMPORTED);
            assertThat(git(wt, "log", "--format=%s", "-1")).isEqualTo("more");
            assertThat(git(wt, "rev-parse", "HEAD~1")).isEqualTo(task.headSha());
            assertThat(git(wt, "status", "--porcelain")).isEmpty();
        } finally {
            workspaces.discard(runDir);
            runs.finish(run.id(), RunStatus.FAILED, "cancelled", BigDecimal.ZERO, 0, 0, 0, null);
        }
        assertThat(containersOf(run.id())).isEmpty();
    }

    @Test
    void anAmendedCommitComesHomeAsTheBranch() throws Exception {
        Task task = verifyingTask(taskService, tasks, worktrees, "Print hello.", "mine.txt", "mine\n");
        Path wt = worktrees.resolve(task.id());
        Run run = start(runs, props, task.id(), RunKind.CI_FIX, "ci_fixer", "{}");
        Path runDir = RUN_ROOT.resolve(run.id().toString());
        try {
            Path clone = workspaces.cloneOf(task.id(), runDir);
            // An agent that rewrote the commit it started from: its HEAD is not a descendant of the start.
            Files.writeString(clone.resolve("mine.txt"), "mine, corrected\n");
            git(clone, "add", "-A");
            git(clone, "-c", "user.email=t@e", "-c", "user.name=T", "commit", "-q", "--amend", "-m", "task: amended");
            String amended = git(clone, "rev-parse", "HEAD");

            assertThat(workspaces.bringHome(run, clone, runDir, task.headSha())).isEqualTo(RunWorkspaces.Home.IMPORTED);

            // The branch becomes what the agent's HEAD is, as in process mode.
            assertThat(git(wt, "rev-parse", "HEAD")).isEqualTo(amended).isNotEqualTo(task.headSha());
            assertThat(git(wt, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("orange/" + task.id());
            assertThat(git(wt, "rev-list", "--count", "main..HEAD")).isEqualTo("1");
            assertThat(Files.readString(wt.resolve("mine.txt"))).isEqualTo("mine, corrected\n");
            assertThat(git(wt, "status", "--porcelain")).isEmpty();
        } finally {
            workspaces.discard(runDir);
            runs.finish(run.id(), RunStatus.FAILED, "cancelled", BigDecimal.ZERO, 0, 0, 0, null);
        }
        assertThat(containersOf(run.id())).isEmpty();
    }

    @Test
    void discardDeletesWhatABuildLeftReadOnly() throws Exception {
        Path runDir = RUN_ROOT.resolve(UUID.randomUUID().toString());
        Path deep = Files.createDirectories(runDir.resolve("workspace/cache/pkg"));
        Files.writeString(deep.resolve("lib.txt"), "x");
        Files.writeString(Files.createDirectories(runDir.resolve("export")).resolve("head.bundle"), "x");
        Files.writeString(runDir.resolve("stream.jsonl"), "{}\n");
        // A link out of the workspace is removed, not followed.
        Files.createSymbolicLink(runDir.resolve("workspace/canary"), CANARY);
        Files.setPosixFilePermissions(deep, PosixFilePermissions.fromString("r-xr-xr-x"));
        Files.setPosixFilePermissions(deep.getParent(), PosixFilePermissions.fromString("r-xr-xr-x"));

        workspaces.discard(runDir);

        assertThat(runDir.resolve("workspace")).doesNotExist();
        assertThat(runDir.resolve("export")).doesNotExist();
        assertThat(runDir.resolve("stream.jsonl")).as("the run's own files stay").exists();
        assertThat(CANARY).exists();
    }

    /** A file the fake wrote under the run's {@code CLAUDE_CONFIG_DIR}; empty when it is not there. */
    static String runFile(RunResult result, String name) {
        Path file = RUN_ROOT.resolve(result.runId() + "/claude-config").resolve(name).normalize();
        try {
            return Files.exists(file) ? Files.readString(file) : "";
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path canary() {
        try {
            return Files.writeString(RunTestSupport.tempDir("container-run-it-canary-").resolve("canary.txt"), "host only\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
