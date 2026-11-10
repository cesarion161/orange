package com.ai.orange.run;

import static com.ai.orange.harness.container.ContainerTestSupport.containersOf;
import static com.ai.orange.harness.container.ContainerTestSupport.docker;
import static com.ai.orange.run.RunTestSupport.git;
import static com.ai.orange.run.RunTestSupport.headInput;
import static com.ai.orange.run.RunTestSupport.runEventsText;
import static com.ai.orange.run.RunTestSupport.runningTask;
import static com.ai.orange.run.RunTestSupport.start;
import static com.ai.orange.run.RunTestSupport.verifyingTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.billing.BillingProperties;
import com.ai.orange.billing.CostEstimator;
import com.ai.orange.evidence.Evidence;
import com.ai.orange.evidence.EvidenceProperties;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.gateway.GatewayClient;
import com.ai.orange.gateway.GatewayProperties;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.HarnessRegistry;
import com.ai.orange.harness.claude.ClaudeCodeAdapter;
import com.ai.orange.harness.container.ContainerRuntime;
import com.ai.orange.harness.container.ContainerTestSupport;
import com.ai.orange.harness.container.DockerCommand;
import com.ai.orange.harness.container.RunnerProperties;
import com.ai.orange.json.Json;
import com.ai.orange.mcp.WorkerMcpConfigWriter;
import com.ai.orange.roles.RolePrompts;
import com.ai.orange.routing.RoutingProperties;
import com.ai.orange.routing.RoutingTable;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A task's runs in container mode: the fake Claude Code runs in a container from the test image,
 * on a clone of the task's branch, and its commits come home as a bundle; the evidence commands
 * run in containers on a clone of the head; the reaper removes what nobody supervises; and the
 * run network reaches nothing of the coordinator but the worker MCP endpoint. Real Docker, no
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
    static final String EVIDENCE_COMMAND = "test -f hello.txt && test ! -e " + CANARY + " && touch built.marker";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
        ContainerTestSupport.register(r);
        // Every test's task keeps its worktree, and there are more tests here than the default cap of 20.
        r.add("orange.worktrees.max-count", () -> "60");
        // Long enough for the fake to start in its container and commit before the kill.
        r.add("orange.runs.timeouts.ci_fix", () -> "10s");
        // Passes only where the host's canary is not: in a container. And it writes where it runs.
        r.add("orange.evidence.commands[0].kind", () -> "tests");
        r.add("orange.evidence.commands[0].command", () -> EVIDENCE_COMMAND);
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
    @Autowired EvidenceRunner evidenceRunner;
    @Autowired RunReaper reaper;
    @Autowired ContainerRuntime containers;
    @Autowired HarnessProperties harnessProps;
    @Autowired RunLifecycle lifecycle;
    @Autowired TaskCancellation cancellation;
    @Autowired RoutingTable routing;
    @Autowired RoutingProperties routingProps;
    @Autowired GatewayClient gateway;
    @Autowired GatewayProperties gatewayProps;
    @Autowired BillingProperties billing;
    @Autowired CostEstimator costs;
    @Autowired RunPreparer preparer;
    @Autowired RolePrompts rolePrompts;
    @Autowired WorkerMcpConfigWriter mcpConfigs;
    @Autowired RunSecrets secrets;
    @Autowired @Qualifier("springSecurityFilterChain") Filter securityChain;

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
                .contains("\"--setting-sources\",\"project,local\"")
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
    void aRunKilledAtItsTimeoutBringsItsCommitsHome() {
        Task task = runningTask(taskService, tasks, "[[fake:commit-hang]]");
        String base = git(BASE_REPO, "rev-parse", "main");

        RunResult result = runner.execute(start(runs, props, task.id(), RunKind.CI_FIX, "ci_fixer", "{}"));

        assertThat(result.reason()).isEqualTo("timeout");
        // As in process mode, where the commit would simply be in the worktree: the next run goes on from it.
        Path wt = worktrees.resolve(task.id());
        assertThat(runFile(result, "fake-committed.txt")).as("the fake committed before the kill").isNotEmpty();
        assertThat(git(wt, "log", "--format=%s|%an", "-1")).isEqualTo("fake: Add a greeting|orange[bot]");
        assertThat(git(wt, "rev-parse", "HEAD~1")).isEqualTo(base);
        assertThat(git(wt, "status", "--porcelain")).isEmpty();
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(after.failures()).isEqualTo(1);
        assertThat(containersOf(result.runId())).isEmpty();
        Path runDir = RUN_ROOT.resolve(result.runId().toString());
        assertThat(runDir.resolve("workspace")).doesNotExist();
        assertThat(runDir.resolve("export")).doesNotExist();
    }

    @Test
    void aCancelledRunBringsNothingHome() throws Exception {
        Task task = runningTask(taskService, tasks, "[[fake:commit-hang]]");
        String base = git(BASE_REPO, "rev-parse", "main");
        Run run = start(runs, props, task.id(), RunKind.IMPLEMENT, task.role(), "{}");
        Path runDir = RUN_ROOT.resolve(run.id().toString());
        CompletableFuture<RunResult> running = CompletableFuture.supplyAsync(() -> runner.execute(run));
        await("the fake's commit", running, () -> Files.exists(runDir.resolve("claude-config/fake-committed.txt")));

        cancellation.cancel(task.id());

        RunResult result = running.get(60, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.reason()).isEqualTo("cancelled");
        // Nobody wants a cancelled task's work: the worktree is where it was.
        assertThat(git(worktrees.resolve(task.id()), "rev-parse", "HEAD")).isEqualTo(base);
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(after.failures()).isZero();
        assertThat(containersOf(run.id())).isEmpty();
        assertThat(runDir.resolve("workspace")).doesNotExist();
        assertThat(runDir.resolve("export")).doesNotExist();
        assertThat(runDir.resolve("mcp.json")).doesNotExist();
    }

    @Test
    void anAgentsContainerDoesNotOutliveItsDockerClient() throws Exception {
        Task task = runningTask(taskService, tasks, "[[fake:commit-hang]]");
        Run run = start(runs, props, task.id(), RunKind.IMPLEMENT, task.role(), "{}");
        Path runDir = RUN_ROOT.resolve(run.id().toString());
        CompletableFuture<RunResult> running = CompletableFuture.supplyAsync(() -> runner.execute(run));
        await("the fake's commit", running, () -> Files.exists(runDir.resolve("claude-config/fake-committed.txt")));
        assertThat(containersOf(run.id())).hasSize(1);

        // What the OOM killer or a lost daemon connection does: the client ends, its container does not.
        ContainerTestSupport.clientOf(DockerCommand.runName(run.id())).destroyForcibly();

        RunResult result = running.get(60, TimeUnit.SECONDS);
        // orange asked for no kill, so to it the agent ended without a result ...
        assertThat(result.reason()).isEqualTo("no_result");
        // ... and the container went before anything read or deleted the clone it was writing.
        assertThat(containersOf(run.id())).isEmpty();
        assertThat(git(worktrees.resolve(task.id()), "log", "--format=%s", "-1")).isEqualTo("fake: Add a greeting");
        assertThat(runDir.resolve("workspace")).doesNotExist();
        assertThat(runDir.resolve("export")).doesNotExist();
        assertThat(runDir.resolve("mcp.json")).doesNotExist();
    }

    @Test
    void doneWithCommitsThatDoNotComeHomeIsNoCommits() throws Exception {
        Task task = runningTask(taskService, tasks, "Print hello.");
        // An earlier attempt left a commit on the branch.
        RunResult first = runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer",
                "{\"feedback\":[{\"source\":\"test\",\"summary\":\"[[fake:report-failed]]\"}]}"));
        assertThat(first.reason()).isEqualTo("reported_failed");
        Path wt = worktrees.resolve(task.id());
        String afterFirst = git(wt, "rev-parse", "HEAD");

        // This one moves its HEAD behind where it started, which no bundle can carry, and reports done.
        RunResult second = runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer",
                "{\"feedback\":[{\"source\":\"test\",\"summary\":\"[[fake:rewind]]\"}]}"));

        // The branch has a commit ahead of the base, but it is not this run's work: nothing goes to verification.
        assertThat(second.status()).isEqualTo(RunStatus.FAILED);
        assertThat(second.reason()).isEqualTo("no_commits");
        assertThat(git(wt, "rev-parse", "HEAD")).as("the task's branch keeps its commits").isEqualTo(afterFirst);
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(after.headSha()).isNull();
        assertThat(after.failures()).isEqualTo(2);
        assertThat(pause.current()).isEmpty();
        assertThat(containersOf(second.runId())).isEmpty();
    }

    @Test
    void anAgentWithoutItsImageIsContainerUnavailableAndNothingCounts() {
        RunnerProperties.Container d = containers.settings();
        ContainerRuntime noImage = new ContainerRuntime(new RunnerProperties(RunnerProperties.Isolation.CONTAINER,
                new RunnerProperties.Container(d.docker(), "orange-worker-test-missing:nope", d.claudeCommand(),
                        d.network(), d.user(), d.home(), d.memory(), d.cpus(), d.pidsLimit(), d.volumes())));
        WorkerRunner withoutImage = new WorkerRunner(routing, routingProps,
                new HarnessRegistry(List.of(new ClaudeCodeAdapter(harnessProps, noImage))), harnessProps, gateway,
                gatewayProps, billing, costs, props, runs, runEvents, lifecycle, preparer, rolePrompts, mcpConfigs,
                secrets, noImage, new RunWorkspaces(worktrees, noImage, props));
        Task task = runningTask(taskService, tasks, "Print hello.");
        String base = git(BASE_REPO, "rev-parse", "main");
        try {
            RunResult result = withoutImage.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, task.role(), "{}"));

            // docker run answered 125: the agent never ran, and that says nothing about the task.
            assertThat(result.status()).isEqualTo(RunStatus.FAILED);
            assertThat(result.reason()).isEqualTo("container_unavailable");
            Task after = tasks.findById(task.id()).orElseThrow();
            assertThat(after.status()).isEqualTo(TaskStatus.RUNNING);
            assertThat(after.failures()).isZero();
            assertThat(git(worktrees.resolve(task.id()), "rev-parse", "HEAD")).isEqualTo(base);
            // The next run would end the same way: a person builds the image, then resumes.
            assertThat(pause.current()).hasValueSatisfying(p -> {
                assertThat(p.reason()).isEqualTo("container_unavailable");
                assertThat(p.until()).isNull();
            });
            Path runDir = RUN_ROOT.resolve(result.runId().toString());
            assertThat(runDir.resolve("workspace")).doesNotExist();
            assertThat(runDir.resolve("export")).doesNotExist();
            assertThat(runDir.resolve("mcp.json")).doesNotExist();
            assertThat(containersOf(result.runId())).isEmpty();
        } finally {
            pause.resume();
        }
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
        assertThat(runFile(result, "fake-args.json")).contains("\"--permission-mode\",\"dontAsk\"")
                // The worktree is what the reviewed agent committed: its .claude/settings.json and the
                // hooks in it are not loaded, or one of them could submit the verdict with this run's token.
                .contains("\"--setting-sources\",\"user\"").doesNotContain("project,local");
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

    @Test
    void aCloneIsDiscardedOnlyWhenTheRunsContainersAreGone() throws Exception {
        UUID runId = UUID.randomUUID();
        Path runDir = RUN_ROOT.resolve(runId.toString());
        Files.writeString(Files.createDirectories(runDir.resolve("workspace/src")).resolve("a.txt"), "x");
        RunnerProperties.Container d = containers.settings();
        ContainerRuntime silent = new ContainerRuntime(new RunnerProperties(RunnerProperties.Isolation.CONTAINER,
                new RunnerProperties.Container(List.of(RUN_ROOT.resolve("no-such-docker").toString()), d.image(),
                        d.claudeCommand(), d.network(), d.user(), d.home(), d.memory(), d.cpus(), d.pidsLimit(), d.volumes())));

        // Docker does not answer: a container may still be writing the clone, where a directory can
        // turn into a link between two looks. orange does not walk it.
        assertThat(new RunWorkspaces(worktrees, silent, props).discard(runId, runDir)).isFalse();
        assertThat(runDir.resolve("workspace/src/a.txt")).exists();

        // A container of the run that is still there goes first, then the clone.
        leftoverContainer(runId);
        assertThat(workspaces.discard(runId, runDir)).isTrue();
        assertThat(containersOf(runId)).isEmpty();
        assertThat(runDir.resolve("workspace")).doesNotExist();
    }

    // ─────────────────────────── evidence commands ───────────────────────────

    /** A runner with its own commands, and its own runtime when one is given. */
    EvidenceRunner evidenceRunner(ContainerRuntime runtime, EvidenceProperties.Command... commands) {
        return new EvidenceRunner(runs, evidence, worktrees, new EvidenceProperties(List.of(commands), 4000),
                harnessProps, props, lifecycle, System::getenv, null, runtime, new RunWorkspaces(worktrees, runtime, props));
    }

    static EvidenceProperties.Command command(String kind, String command) {
        return new EvidenceProperties.Command(kind, command, Duration.ofSeconds(60));
    }

    Task taskWithHello() {
        return verifyingTask(taskService, tasks, worktrees, "Print hello.", "hello.txt", "hello\n");
    }

    Run verifyRun(Task task) {
        return start(runs, props, task.id(), RunKind.VERIFY, "orange", headInput(task.headSha()));
    }

    @Test
    void evidenceCommandsRunInAContainerOnACloneOfTheHead() {
        Task task = taskWithHello();
        Path wt = worktrees.resolve(task.id());
        assertThat(CANARY).as("on the host the command would fail").exists();

        RunResult result = evidenceRunner.execute(verifyRun(task));

        assertThat(result.status()).as(String.valueOf(result.reason())).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(evidence.findByTaskAndHead(task.id(), task.headSha())).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("tests");
            assertThat(e.command()).isEqualTo(EVIDENCE_COMMAND);
            assertThat(e.passed()).as(e.outputTail()).isTrue();
            assertThat(e.exitCode()).isZero();
            assertThat(e.runId()).isEqualTo(result.runId());
        });
        assertThat(result.outcome()).isEqualTo(new RunOutcome.Verification(false, true, List.of()));

        // What the command wrote went into its clone: the worktree orange runs git in is as it was.
        assertThat(wt.resolve("built.marker")).doesNotExist();
        assertThat(git(wt, "status", "--porcelain")).isEmpty();
        assertThat(git(wt, "rev-parse", "HEAD")).isEqualTo(task.headSha());
        assertThat(RUN_ROOT.resolve(result.runId().toString()).resolve("workspace")).doesNotExist();
        assertThat(containersOf(result.runId())).isEmpty();
        assertThat(runs.findById(result.runId()).orElseThrow().pid()).as("the docker client's, for the reaper").isNotNull();
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.VERIFYING);
        assertThat(after.failures()).isZero();
        assertThat(pause.current()).isEmpty();
    }

    @Test
    void whatACommandSeesIsItsCloneAndWhatItReturnsIsEvidence() {
        Task task = taskWithHello();
        Run run = verifyRun(task);
        Path clone = RUN_ROOT.resolve(run.id().toString()).resolve("workspace");

        RunResult result = evidenceRunner(containers, command("custom", "id -u; pwd; ls; env"),
                command("tests", "echo 'FooTest > bar FAILED'; exit 3"),
                // sh's own "not found" is the repo's failure, not Docker's.
                command("build", "no-such-command-of-this-repo")).execute(run);

        assertThat(result.status()).as(String.valueOf(result.reason())).isEqualTo(RunStatus.SUCCEEDED);
        RunOutcome.Verification v = (RunOutcome.Verification) result.outcome();
        assertThat(v.passed()).isFalse();
        assertThat(v.gaps()).extracting(EvidenceGap::source).containsExactly("tests", "build");
        assertThat(v.gaps()).extracting(EvidenceGap::exitCode).containsExactly(3, 127);
        assertThat(v.gaps().getFirst().outputTail()).contains("FooTest > bar FAILED");
        List<Evidence> rows = evidence.findByTaskAndHead(task.id(), task.headSha());
        assertThat(rows).extracting(Evidence::kind).containsExactlyInAnyOrder("custom", "tests", "build");

        List<String> seen = rows.stream().filter(e -> e.kind().equals("custom")).findFirst().orElseThrow()
                .outputTail().lines().toList();
        assertThat(seen.get(0)).as("the uid it ran as").matches("\\d+").isNotEqualTo("0");
        assertThat(seen.get(1)).as("its working directory").isEqualTo(clone.toString());
        assertThat(seen).contains("hello.txt", "README.md", "HOME=/home/agent");
        // Nothing of orange's environment, not even the allowlisted part.
        Map<String, String> env = new java.util.HashMap<>();
        seen.stream().filter(l -> l.contains("=")).forEach(l -> env.put(l.substring(0, l.indexOf('=')), l.substring(l.indexOf('=') + 1)));
        // The image's locale and the shell's own bookkeeping may be the host's too.
        Set<String> itsOwn = Set.of("LANG", "LC_ALL", "LANGUAGE", "SHLVL", "PWD", "OLDPWD", "_");
        List<String> inherited = new ArrayList<>();
        for (Map.Entry<String, String> host : System.getenv().entrySet()) {
            if (itsOwn.contains(host.getKey())) continue;
            // Names only: a value here may be a credential of whoever runs the tests.
            if (host.getValue().equals(env.get(host.getKey()))) inherited.add(host.getKey());
        }
        assertThat(inherited).as("host variables that reached the command with their value").isEmpty();
        assertThat(env).doesNotContainKeys("DOCKER_HOST", "USER", "TMPDIR", "CLAUDE_CODE_OAUTH_TOKEN");

        // A failed check sends the task back and counts, as on the host.
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(after.failures()).isEqualTo(1);
        assertThat(clone).doesNotExist();
        assertThat(containersOf(run.id())).isEmpty();
        assertThat(pause.current()).isEmpty();
    }

    @Test
    void aCommandThatHangsIsKilledAtItsTimeoutWithItsContainer() {
        Task task = taskWithHello();
        Run run = verifyRun(task);
        long started = System.nanoTime();

        RunResult result = evidenceRunner(containers,
                new EvidenceProperties.Command("tests", "echo started; sleep 600", Duration.ofSeconds(4))).execute(run);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(60));
        RunOutcome.Verification v = (RunOutcome.Verification) result.outcome();
        assertThat(v.gaps()).singleElement().satisfies(g -> {
            assertThat(g.exitCode()).isEqualTo(-1);
            assertThat(g.outputTail()).contains("started").contains("timed out");
        });
        assertThat(containersOf(run.id())).isEmpty();
        assertThat(RUN_ROOT.resolve(run.id().toString()).resolve("workspace")).doesNotExist();
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isEqualTo(1);
    }

    @Test
    void stoppingKillsARunningCommandAndItsContainer() throws Exception {
        Task task = taskWithHello();
        Run run = verifyRun(task);
        EvidenceRunner runner = evidenceRunner(containers,
                new EvidenceProperties.Command("tests", "sleep 600", Duration.ofMinutes(10)));
        CompletableFuture<RunResult> verifying = CompletableFuture.supplyAsync(() -> runner.execute(run));
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (runner.liveCommands() == 0 || containersOf(run.id()).isEmpty()) {
            assertThat(System.nanoTime()).as("the command's container never started").isLessThan(deadline);
            Thread.sleep(100);
        }

        runner.shutdown();

        RunResult result = verifying.get(30, TimeUnit.SECONDS);
        assertThat(result.reason()).isEqualTo("coordinator_restart");
        assertThat(containersOf(run.id())).isEmpty();
        assertThat(evidence.findByTask(task.id())).as("a killed command is no verdict on the work").isEmpty();
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isZero();
        assertThat(RUN_ROOT.resolve(run.id().toString()).resolve("workspace")).doesNotExist();
    }

    @Test
    void aCommandsContainerDoesNotOutliveItsDockerClient() throws Exception {
        Task task = taskWithHello();
        Run run = verifyRun(task);
        EvidenceRunner runner = evidenceRunner(containers,
                new EvidenceProperties.Command("tests", "sleep 600", Duration.ofMinutes(10)));
        CompletableFuture<RunResult> verifying = CompletableFuture.supplyAsync(() -> runner.execute(run));
        await("the command's container", verifying, () -> runner.liveCommands() > 0 && !containersOf(run.id()).isEmpty());

        // The client ends without orange asking for it; the container would go on in the clone.
        ContainerTestSupport.clientOf(DockerCommand.runName(run.id()) + "-c1").destroyForcibly();

        RunResult result = verifying.get(60, TimeUnit.SECONDS);
        assertThat(containersOf(run.id())).isEmpty();
        assertThat(RUN_ROOT.resolve(run.id().toString()).resolve("workspace")).doesNotExist();
        // To orange the command ended with the client's exit code, as it would had the kernel killed the command.
        assertThat(((RunOutcome.Verification) result.outcome()).gaps()).extracting(EvidenceGap::exitCode).containsExactly(137);
    }

    @Test
    void aLeaseTheReaperTakesFromALiveVerifyRunIsNoVerdictOnTheWork() throws Exception {
        Task task = taskWithHello();
        Run run = verifyRun(task);
        EvidenceRunner runner = evidenceRunner(containers,
                new EvidenceProperties.Command("tests", "sleep 600", Duration.ofMinutes(10)));
        CompletableFuture<RunResult> verifying = CompletableFuture.supplyAsync(() -> runner.execute(run));
        await("the command's container", verifying, () -> runner.liveCommands() > 0 && !containersOf(run.id()).isEmpty());

        // The database was away or the laptop slept: the lease ran out while its supervisor lives.
        assertThat(runs.heartbeat(run.id(), props.workerId(), Duration.ofSeconds(-5))).isTrue();
        reaper.reapExpired();

        RunResult result = verifying.get(60, TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.reason()).isEqualTo("lease_expired");
        // The command ended because its container was taken away, not because the work is wrong.
        assertThat(evidence.findByTask(task.id())).isEmpty();
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.VERIFYING);
        assertThat(after.failures()).isZero();
        assertThat(containersOf(run.id())).isEmpty();
        assertThat(RUN_ROOT.resolve(run.id().toString()).resolve("workspace")).doesNotExist();
    }

    @Test
    void aMissingImageIsNoEvidenceAndPausesTheScheduler() {
        RunnerProperties.Container d = containers.settings();
        // docker run answers 125: there is no such image, and orange never pulls one.
        dockerCannotRunTheCommand(new RunnerProperties.Container(d.docker(), "orange-worker-test-missing:nope",
                d.claudeCommand(), d.network(), d.user(), d.home(), d.memory(), d.cpus(), d.pidsLimit(), d.volumes()), true);
    }

    @Test
    void aMissingDockerIsNoEvidenceAndPausesTheScheduler() {
        RunnerProperties.Container d = containers.settings();
        dockerCannotRunTheCommand(new RunnerProperties.Container(List.of(RUN_ROOT.resolve("no-such-docker").toString()),
                d.image(), d.claudeCommand(), d.network(), d.user(), d.home(), d.memory(), d.cpus(), d.pidsLimit(),
                d.volumes()), false);
    }

    /** @param dockerAnswers whether docker can at least say that the run has no container left */
    private void dockerCannotRunTheCommand(RunnerProperties.Container settings, boolean dockerAnswers) {
        Task task = taskWithHello();
        Run run = verifyRun(task);
        ContainerRuntime broken = new ContainerRuntime(new RunnerProperties(RunnerProperties.Isolation.CONTAINER, settings));
        try {
            // The second command is never tried: the run ends with the first.
            RunResult result = evidenceRunner(broken, command("tests", "true"), command("build", "true")).execute(run);

            assertThat(result.status()).isEqualTo(RunStatus.FAILED);
            assertThat(result.reason()).isEqualTo("container_unavailable");
            // Docker's failure says nothing about the work: no row, no gap, nothing counted.
            assertThat(evidence.findByTask(task.id())).isEmpty();
            Task after = tasks.findById(task.id()).orElseThrow();
            assertThat(after.status()).isEqualTo(TaskStatus.VERIFYING);
            assertThat(after.failures()).isZero();
            // The next run would fail the same way: a person builds the image or starts Docker, then resumes.
            assertThat(pause.current()).hasValueSatisfying(p -> {
                assertThat(p.reason()).isEqualTo("container_unavailable");
                assertThat(p.until()).isNull();
            });
            Path runDir = RUN_ROOT.resolve(run.id().toString());
            if (dockerAnswers) {
                assertThat(runDir.resolve("workspace")).doesNotExist();
            } else {
                // Nobody can say that no container holds the clone, so nobody walks it: it goes with the run's directory.
                assertThat(runDir.resolve("workspace")).exists();
                workspaces.discard(runDir);
            }
            assertThat(containersOf(run.id())).isEmpty();
        } finally {
            pause.resume();
        }
    }

    // ─────────────────────────── the reaper ───────────────────────────

    /** What a coordinator that died left behind: a container with the run's label and nobody waiting for it. */
    static String leftoverContainer(UUID runId) {
        return docker("run", "-d", "--rm", "--network", "none", "--label", "orange.run=" + runId,
                ContainerTestSupport.image(), "sleep", "600").strip();
    }

    @Test
    void theReaperRemovesAnOrphansContainersAndItsClone() throws Exception {
        Task task = runningTask(taskService, tasks, "x");
        Task own = runningTask(taskService, tasks, "y");
        Run orphan = runs.insertRunning(RunKind.IMPLEMENT, "implementer", task.id(), "previous-instance",
                Duration.ofMinutes(5), JSONB.valueOf("{}"));
        Run mine = start(runs, props, own.id(), RunKind.IMPLEMENT, "implementer", "{}");
        try {
            leftoverContainer(orphan.id());
            leftoverContainer(orphan.id());
            leftoverContainer(mine.id());
            assertThat(containersOf(orphan.id())).hasSize(2);
            // And the clone its agent worked on, with what a build left read-only, next to the run's own files.
            Path orphanDir = RUN_ROOT.resolve(orphan.id().toString());
            Path built = Files.createDirectories(orphanDir.resolve("workspace/build"));
            Files.writeString(built.resolve("out.txt"), "x");
            Files.setPosixFilePermissions(built, PosixFilePermissions.fromString("r-xr-xr-x"));
            Files.writeString(orphanDir.resolve("stream.jsonl"), "{}\n");
            Path mineDir = RUN_ROOT.resolve(mine.id().toString());
            Files.createDirectories(mineDir.resolve("workspace"));

            reaper.failOrphans();

            assertThat(containersOf(orphan.id())).isEmpty();
            assertThat(orphanDir.resolve("workspace")).as("nobody else would delete it before retention").doesNotExist();
            assertThat(orphanDir.resolve("stream.jsonl")).exists();
            assertThat(mineDir.resolve("workspace")).as("a run this coordinator supervises keeps its clone").exists();
            Run failed = runs.findById(orphan.id()).orElseThrow();
            assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
            assertThat(failed.error()).isEqualTo("coordinator_restart");
            assertThat(tasks.findById(task.id()).orElseThrow().failures()).isZero();
            // A run this coordinator supervises keeps its container.
            assertThat(containersOf(mine.id())).hasSize(1);
            assertThat(runs.findById(mine.id()).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
        } finally {
            containers.removeByRun(orphan.id());
            containers.removeByRun(mine.id());
            runs.finish(mine.id(), RunStatus.FAILED, "cancelled", BigDecimal.ZERO, 0, 0, 0, null);
        }
    }

    @Test
    void theReaperRemovesTheContainersOfAnExpiredLease() {
        Task task = runningTask(taskService, tasks, "x");
        Task live = runningTask(taskService, tasks, "y");
        Run expired = runs.insertRunning(RunKind.VERIFY, "orange", task.id(), props.workerId(), Duration.ofSeconds(-5),
                JSONB.valueOf("{}"));
        Run mine = start(runs, props, live.id(), RunKind.IMPLEMENT, "implementer", "{}");
        try {
            leftoverContainer(expired.id());
            leftoverContainer(mine.id());

            reaper.reapExpired();

            assertThat(containersOf(expired.id())).isEmpty();
            Run failed = runs.findById(expired.id()).orElseThrow();
            assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
            assertThat(failed.error()).isEqualTo("lease_expired");
            assertThat(tasks.findById(task.id()).orElseThrow().failures()).isZero();
            assertThat(containersOf(mine.id())).as("a live lease keeps its container").hasSize(1);
            assertThat(runs.findById(mine.id()).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
        } finally {
            containers.removeByRun(expired.id());
            containers.removeByRun(mine.id());
            runs.finish(mine.id(), RunStatus.FAILED, "cancelled", BigDecimal.ZERO, 0, 0, 0, null);
        }
    }

    // ─────────────────────────── the network guard ───────────────────────────

    @Test
    void aRequestFromTheRunNetworkReachesOnlyTheWorkerMcp() throws Exception {
        // The coordinator looked at the run network when it started.
        String subnet = containers.subnets().stream().filter(s -> s.contains(".")).findFirst().orElseThrow();
        byte[] address = InetAddress.ofLiteral(subnet.substring(0, subnet.indexOf('/'))).getAddress();
        address[3] |= 2;
        String aRun = InetAddress.getByAddress(address).getHostAddress();

        for (String[] call : new String[][]{{"GET", "/tasks"}, {"POST", "/scheduler/resume"}, {"GET", "/sessions"},
                {"POST", "/mcp/steering"}, {"GET", "/actuator/health"}, {"POST", "/mcp/worker/"}}) {
            MockHttpServletResponse refused = new MockHttpServletResponse();
            MockFilterChain behind = new MockFilterChain();
            securityChain.doFilter(request(call[0], call[1], aRun), refused, behind);
            assertThat(refused.getStatus()).as(call[1]).isEqualTo(403);
            assertThat(refused.getContentAsString()).as(call[1]).isEqualTo("{\"error\":\"runs reach only /mcp/worker\"}");
            assertThat(behind.getRequest()).as(call[1] + " went no further").isNull();
        }

        // Its own endpoint is open to it (the run's token is checked behind the chain) ...
        MockFilterChain worker = new MockFilterChain();
        securityChain.doFilter(request("POST", "/mcp/worker", aRun), new MockHttpServletResponse(), worker);
        assertThat(worker.getRequest()).isNotNull();
        // ... and everyone else gets what they got before.
        MockFilterChain operator = new MockFilterChain();
        securityChain.doFilter(request("GET", "/tasks", "127.0.0.1"), new MockHttpServletResponse(), operator);
        assertThat(operator.getRequest()).isNotNull();
    }

    static MockHttpServletRequest request(String method, String uri, String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    // ─────────────────────────── helpers ───────────────────────────

    /** Waits until {@code ready} holds; fails when the run ends first or a minute has passed. */
    static void await(String what, CompletableFuture<?> running, BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!ready.getAsBoolean()) {
            assertThat(running.isDone()).as("the run ended before " + what).isFalse();
            assertThat(System.nanoTime()).as("waiting for " + what).isLessThan(deadline);
            Thread.sleep(100);
        }
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
