package com.ai.orange.e2e;

import static com.ai.orange.harness.container.ContainerTestSupport.containersOf;
import static com.ai.orange.run.RunTestSupport.git;
import static com.ai.orange.run.RunTestSupport.runEventTypes;
import static com.ai.orange.run.RunTestSupport.runEventsText;
import static com.ai.orange.run.RunTestSupport.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunExit;
import com.ai.orange.harness.RunSpec;
import com.ai.orange.harness.Sandbox;
import com.ai.orange.harness.claude.AuthFailure;
import com.ai.orange.harness.claude.ClaudeCodeAdapter;
import com.ai.orange.harness.container.ContainerRuntime;
import com.ai.orange.harness.container.DockerCommand;
import com.ai.orange.json.Json;
import com.ai.orange.mcp.RunTokens;
import com.ai.orange.mcp.WorkerMcpConfigWriter;
import com.ai.orange.roles.ToolPolicy;
import com.ai.orange.routing.Effort;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunEventRepository;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunOutcome;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunResult;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunTestSupport;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.WorkerRunner;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The real worker image ({@code orange/worker/build.sh}; {@code ORANGE_WORKER_IMAGE} or
 * {@code orange-worker:local}) in container mode, which the suite's fake cannot stand in for: that
 * the Claude Code in the image takes orange's command line as a user who is not root and reaches
 * the worker MCP endpoint from inside a container (an invalid token: no secret, no model call),
 * and, with {@code CLAUDE_CODE_OAUTH_TOKEN}, one real implement run on a throwaway repository,
 * from the clone to the commit that comes home. Both are skipped when the image is not built.
 * Run with {@code ./gradlew liveTest --tests '*ContainerLiveIT'}.
 */
@Tag("live")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ContainerLiveIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("container-live-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("container-live-it-wt-");
    /** A file of the host, in a directory no run is given. */
    static final Path CANARY = canary();
    static final String HELLO_LINE = "Hello from an orange container run.";
    static final String PROBE_COMMAND = "id -u; test -e " + CANARY + " && echo CANARY_VISIBLE || echo CANARY_HIDDEN";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("orange.runner.isolation", () -> "container");
        r.add("orange.harness.run-root", RUN_ROOT::toString);
        r.add("orange.worktrees.base-repo", BASE_REPO::toString);
        r.add("orange.worktrees.root", WORKTREES::toString);
        r.add("orange.worktrees.base-branch", () -> "main");
        // This coordinator's own port, whatever orange/.env says about a deployment's.
        r.add("orange.mcp.worker-url", () -> "");
        r.add("orange.runs.timeouts.implement", () -> "10m");
    }

    @Autowired ClaudeCodeAdapter adapter;
    @Autowired ContainerRuntime containers;
    @Autowired WorkerMcpConfigWriter configWriter;
    @Autowired WorkerRunner runner;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired RunEventRepository runEvents;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired WorktreeService worktrees;

    @BeforeEach
    void theImageIsBuilt() {
        String image = containers.settings().image();
        assumeTrue(imageExists(image), "there is no image " + image + "; build it: orange/worker/build.sh");
    }

    @Test
    void realClaudeInTheImageTakesOrangesCommandLineAndReachesTheWorkerMcp() throws Exception {
        UUID task = taskService.createGraph(
                List.of(new TaskDef("live-" + UUID.randomUUID(), "Live container check", null, "implementer", null, null)),
                List.of()).get(0).id();
        Run run = runs.insertRunning(RunKind.IMPLEMENT, "implementer", task, props.workerId(), Duration.ofMinutes(5),
                JSONB.valueOf("{}"));
        String token = RunTokens.newToken();
        runs.recordRoute(run.id(), "small", "claude-haiku-5-5", "low", "claude-code", "subscription",
                new BigDecimal("1"), null, RunTokens.hash(token), null);

        // What WorkerRunner gives a contained implement run: a directory of its own under the run's,
        // the MCP config with the coordinator spelled as a container reaches it, a writable sandbox.
        Path runDir = RUN_ROOT.resolve(run.id().toString()).toAbsolutePath();
        Path cwd = Files.createDirectories(runDir.resolve("workspace"));
        Path mcpConfig = configWriter.write(runDir, token, true);
        ToolPolicy policy = ToolPolicy.forKind(RunKind.IMPLEMENT).inContainer();
        RunSpec spec = new RunSpec(run.id(), "Reply with exactly: ORANGE_OK", "You are orange's container check.",
                "claude-haiku-5-5", "claude-haiku-5-5", Effort.LOW, cwd, policy.tools(), policy.allowedTools(),
                List.of(), policy.permissionMode(), mcpConfig,
                new Credentials.Subscription("sk-ant-oat01-orange-container-check-not-a-real-token"),
                Duration.ofMinutes(2), List.of(token),
                new Sandbox(false, DockerCommand.labels(run.id(), RunKind.IMPLEMENT.dbValue(), props.workerId())),
                policy.projectSettings());

        List<RunEvent> events = new CopyOnWriteArrayList<>();
        RunExit exit;
        try {
            exit = adapter.start(spec, events::add).exit().get(3, TimeUnit.MINUTES);
        } finally {
            Files.deleteIfExists(mcpConfig);
        }

        String stderr = read(runDir.resolve("stderr.log"));
        assertThat(Files.exists(runDir.resolve("stream.jsonl")) ? Files.readAllLines(runDir.resolve("stream.jsonl")) : List.<String>of())
                .as("Claude Code wrote no stream; exit %s, stderr: %s", exit.exitCode(), stderr).isNotEmpty();
        JsonNode init = Json.mapper().readTree(Files.readAllLines(runDir.resolve("stream.jsonl")).getFirst());
        System.out.println("image=" + containers.settings().image()
                + " claude_code_version=" + init.path("claude_code_version").asText()
                + " user=" + containers.user() + " mcp_servers=" + init.path("mcp_servers") + " exit=" + exit.exitCode());

        assertThat(stderr).as("claude rejected part of the command line").doesNotContainIgnoringCase("error:");
        assertThat(init.path("subtype").asText()).isEqualTo("init");
        // The container is the boundary; Claude Code refuses this mode to root, and the run is not root.
        assertThat(init.path("permissionMode").asText()).isEqualTo("bypassPermissions");
        // The same path inside and outside.
        assertThat(init.path("cwd").asText()).isEqualTo(cwd.toString());
        assertThat(init.path("mcp_servers")).as("mcp_servers: %s", init.path("mcp_servers"))
                .anySatisfy(s -> {
                    assertThat(s.path("name").asText()).isEqualTo("orange");
                    assertThat(s.path("status").asText()).isEqualTo("connected");
                });
        List<String> toolNames = new ArrayList<>();
        init.path("tools").forEach(t -> toolNames.add(t.asText()));
        assertThat(toolNames).contains("Bash", "mcp__orange__report_result", "mcp__orange__request_human");
        assertThat(toolNames).doesNotContain("mcp__orange__submit_review");
        assertThat(init.path("plugins")).as("only Claude Code's built-in plugins load")
                .allSatisfy(p -> assertThat(p.path("source").asText()).endsWith("@builtin"));

        // Anthropic rejects the token before any model call.
        assertThat(exit.killReason()).isNull();
        assertThat(exit.result()).as("exit %s, stderr: %s", exit.exitCode(), stderr).isNotNull();
        assertThat(exit.result().isError()).isTrue();
        assertThat(AuthFailure.matches(exit.result().text())).as("result: %s", exit.result().text()).isTrue();
        assertThat(events.stream().filter(RunEvent.ToolUse.class::isInstance)).isEmpty();
        assertThat(runs.findById(run.id()).orElseThrow().outcome()).as("no model call, so no outcome").isNull();
        assertThat(cwd.toFile().list()).as("nothing ran, so nothing was written").isEmpty();

        assertThat(containersOf(run.id())).as("containers of run %s", run.id()).isEmpty();
    }

    /**
     * A review reads the tree the reviewed agent committed. Claude Code runs the hooks of that tree's
     * {@code .claude/settings.json} without asking, and a hook there has the reviewer's run token in
     * reach (the MCP config is mounted next to the config directory): it could submit the verdict
     * itself. So a review names only the user's settings, of which its run has none. An invalid
     * token: no secret, no model call.
     */
    @Test
    void aReviewRunsNoHookOfTheTreeItReads() throws Exception {
        Path tree = Files.createDirectories(RUN_ROOT.resolve("reviewed-" + UUID.randomUUID()).toAbsolutePath());
        Files.writeString(Files.createDirectories(tree.resolve(".claude")).resolve("settings.json"), """
                {"hooks":{"SessionStart":[{"hooks":[{"type":"command","command":"touch \\"$CLAUDE_CONFIG_DIR/hook-ran\\""}]}]}}
                """);
        ToolPolicy policy = ToolPolicy.forKind(RunKind.DIFF_REVIEW).inContainer();

        // What this check rests on: with the tree's settings, as every run was started before, the hook runs.
        assertThat(hookRuns(tree, policy, true)).as("Claude Code no longer runs a project's SessionStart hook").isTrue();
        assertThat(policy.projectSettings()).isFalse();
        assertThat(hookRuns(tree, policy, policy.projectSettings())).as("a hook of the reviewed tree ran").isFalse();
    }

    /** Starts the reviewer on {@code tree}, mounted read-only, and says whether the tree's hook left its mark. */
    private boolean hookRuns(Path tree, ToolPolicy policy, boolean projectSettings) throws Exception {
        UUID runId = UUID.randomUUID();
        Path runDir = RUN_ROOT.resolve(runId.toString()).toAbsolutePath();
        RunSpec spec = new RunSpec(runId, "Reply with exactly: ORANGE_OK", "You are orange's container check.",
                "claude-haiku-5-5", "claude-haiku-5-5", Effort.LOW, tree, policy.tools(), policy.allowedTools(),
                List.of(), policy.permissionMode(), null,
                new Credentials.Subscription("sk-ant-oat01-orange-container-check-not-a-real-token"),
                Duration.ofMinutes(2), List.of(),
                new Sandbox(true, DockerCommand.labels(runId, RunKind.DIFF_REVIEW.dbValue(), props.workerId())),
                projectSettings);

        RunExit exit = adapter.start(spec, event -> { }).exit().get(3, TimeUnit.MINUTES);

        String stderr = read(runDir.resolve("stderr.log"));
        assertThat(stderr).as("claude rejected part of the command line").doesNotContainIgnoringCase("error:");
        // Anthropic rejects the token before any model call.
        assertThat(exit.result()).as("exit %s, stderr: %s", exit.exitCode(), stderr).isNotNull();
        assertThat(AuthFailure.matches(exit.result().text())).as("result: %s", exit.result().text()).isTrue();
        assertThat(containersOf(runId)).as("containers of run %s", runId).isEmpty();
        return Files.exists(runDir.resolve("claude-config/hook-ran"));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "CLAUDE_CODE_OAUTH_TOKEN", matches = ".+")
    void oneRealImplementRunInAContainer() throws Exception {
        String token = System.getenv("CLAUDE_CODE_OAUTH_TOKEN");
        String description = """
                Create two files in the root of this repository:

                1. `HELLO.md`, containing exactly this one line:

                   %s

                2. `probe.txt`, containing the output of this shell command, run with Bash exactly as it is written here:

                   %s

                Commit both files in one commit and report that you are done. Change nothing else.
                """.formatted(HELLO_LINE, PROBE_COMMAND);
        // A chore: the small tier at low effort.
        Task task = taskService.createGraph(List.of(new TaskDef("live-" + UUID.randomUUID(), "Add HELLO.md and probe.txt",
                description, TaskDef.CHORE, null, null)), List.of()).get(0);
        tasks.transitionStatus(task.id(), TaskStatus.READY, TaskStatus.RUNNING);
        assertThat(CANARY).as("the canary is there for whoever runs on the host").exists();
        String base = git(BASE_REPO, "rev-parse", "main");

        long started = System.nanoTime();
        RunResult result = runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, task.role(), "{}"));
        long seconds = Duration.ofNanos(System.nanoTime() - started).toSeconds();

        Path runDir = RUN_ROOT.resolve(result.runId().toString());
        String stream = read(runDir.resolve("stream.jsonl"));
        String stderr = read(runDir.resolve("stderr.log"));
        Run row = runs.findById(result.runId()).orElseThrow();
        System.out.println("image=" + containers.settings().image() + " user=" + containers.user()
                + " model=" + row.model() + " effort=" + row.effort() + " status=" + result.status()
                + " reason=" + result.reason() + " cost_usd=" + result.costUsd() + " seconds=" + seconds);

        assertThat(result.status()).as("reason %s, stderr: %s", result.reason(), stderr.replace(token, "***"))
                .isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outcome()).isInstanceOfSatisfying(RunOutcome.Result.class,
                o -> assertThat(o.status()).isEqualTo("done"));
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.VERIFYING);

        // The commit came home: the task's worktree, which only orange writes, is at it.
        Path wt = worktrees.resolve(task.id());
        assertThat(after.headSha()).isEqualTo(git(wt, "rev-parse", "HEAD")).isNotEqualTo(base);
        assertThat(git(wt, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("orange/" + task.id());
        assertThat(git(wt, "status", "--porcelain")).isEmpty();
        assertThat(git(wt, "show", "HEAD:HELLO.md").strip()).isEqualTo(HELLO_LINE);
        // The agent's Bash ran in the container: not as root, and without the host's files.
        List<String> probe = git(wt, "show", "HEAD:probe.txt").lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        assertThat(probe).as("probe.txt").hasSize(2);
        assertThat(probe.get(0)).matches("\\d+").isNotEqualTo("0").isEqualTo(containers.user().split(":")[0]);
        assertThat(probe.get(1)).isEqualTo("CANARY_HIDDEN");

        assertThat(runEventTypes(runEvents, result.runId())).contains("init", "tool_use", "result");
        // The token is nowhere in what the run left.
        assertThat(runEventsText(runEvents, result.runId()).contains(token)).as("the token is in run_events").isFalse();
        assertThat(stream.contains(token)).as("the token is in stream.jsonl").isFalse();
        assertThat(stderr.contains(token)).as("the token is in stderr.log").isFalse();
        JsonNode init = Json.mapper().readTree(stream.lines().findFirst().orElseThrow());
        System.out.println("claude_code_version=" + init.path("claude_code_version").asText());
        assertThat(init.path("permissionMode").asText()).isEqualTo("bypassPermissions");
        assertThat(init.path("cwd").asText()).isEqualTo(runDir.toAbsolutePath().resolve("workspace").toString());

        // Nothing of the run is left: no clone, no bundle, no token file, no container.
        assertThat(runDir.resolve("workspace")).doesNotExist();
        assertThat(runDir.resolve("export")).doesNotExist();
        assertThat(runDir.resolve("mcp.json")).doesNotExist();
        assertThat(containersOf(result.runId())).as("containers of run %s", result.runId()).isEmpty();
    }

    /** Asks the daemon with the Docker CLI, as orange does; a missing CLI is a missing image too. */
    private static boolean imageExists(String image) {
        try {
            Process p = new ProcessBuilder("docker", "image", "inspect", image)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            p.getOutputStream().close();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String read(Path file) throws IOException {
        return Files.exists(file) ? Files.readString(file) : "";
    }

    private static Path canary() {
        try {
            return Files.writeString(RunTestSupport.tempDir("container-live-it-canary-").resolve("canary.txt"), "host only\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
