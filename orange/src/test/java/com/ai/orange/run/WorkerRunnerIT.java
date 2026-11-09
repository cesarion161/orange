package com.ai.orange.run;

import static com.ai.orange.run.RunTestSupport.git;
import static com.ai.orange.run.RunTestSupport.runEventTypes;
import static com.ai.orange.run.RunTestSupport.runEventsText;
import static com.ai.orange.run.RunTestSupport.runningTask;
import static com.ai.orange.run.RunTestSupport.start;
import static com.ai.orange.run.RunTestSupport.taskEventTypes;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WorkerRunnerIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("worker-run-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("worker-run-it-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
        r.add("orange.runs.heartbeat", () -> "1s");
        r.add("orange.runs.timeouts.ci_fix", () -> "3s");
    }

    @Autowired WorkerRunner runner;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired RunEventRepository runEvents;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository taskEvents;
    @Autowired SchedulerPause pause;
    @Autowired WorktreeService worktrees;

    @BeforeEach
    void unpause() {
        pause.resume();
    }

    RunResult implement(Task task) {
        return runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, task.role(), "{}"));
    }

    @Test
    void implementRunRecordsTheMcpOutcome() throws Exception {
        Task task = runningTask(taskService, tasks, "Print hello.");
        RunResult result = implement(task);

        assertThat(result.status()).as(String.valueOf(result.reason())).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outcome()).isEqualTo(new RunOutcome.Result("done", "Implemented: Add a greeting", null));
        assertThat(runEventTypes(runEvents, result.runId())).contains("init", "assistant", "result");
        assertThat(RUN_ROOT.resolve(result.runId() + "/mcp.json")).as("the token file is gone").doesNotExist();

        Run row = runs.findById(result.runId()).orElseThrow();
        assertThat(row.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(row.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(row.effort()).isEqualTo("high");
        assertThat(row.tier()).isEqualTo("medium");
        assertThat(row.auth()).isEqualTo("subscription");
        assertThat(row.costUsd()).isEqualByComparingTo("0.01");
        assertThat(taskEventTypes(taskEvents, task.id())).contains("run_started", "run_finished");

        // The work landed in the task's own worktree, on its own branch.
        Path wt = worktrees.resolve(task.id());
        assertThat(wt.resolve("orange-fake.txt")).exists();
        assertThat(git(wt, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("orange/" + task.id());

        String env = Files.readString(RUN_ROOT.resolve(result.runId() + "/claude-config/fake-env.json"));
        assertThat(env).contains("\"CLAUDE_CODE_OAUTH_TOKEN\":\"test-plan-token\"");
        assertThat(runEventsText(runEvents, result.runId())).doesNotContain("test-plan-token");
    }

    @Test
    void noOutcomeIsAModelFailure() {
        Task task = runningTask(taskService, tasks, "[[fake:no-outcome]]");
        RunResult result = implement(task);

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.reason()).isEqualTo("no_outcome");
        assertThat(runs.findById(result.runId()).orElseThrow().error()).isEqualTo("no_outcome");
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isEqualTo(1);
    }

    @Test
    void reportedFailureCounts() {
        Task task = runningTask(taskService, tasks, "[[fake:report-failed]]");
        RunResult result = implement(task);

        assertThat(result.reason()).isEqualTo("reported_failed");
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isEqualTo(1);
    }

    @Test
    void blockedMovesTheTaskToNeedsHuman() {
        Task task = runningTask(taskService, tasks, "[[fake:blocked]]");
        RunResult result = implement(task);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.NEEDS_HUMAN);
        assertThat(taskEvents.findByTaskSince(task.id(), 0, 100))
                .anySatisfy(e -> {
                    assertThat(e.eventType()).isEqualTo("needs_human");
                    assertThat(e.payload().data()).contains("Postgres or SQLite");
                });
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isZero();
    }

    @Test
    void budgetKill() {
        // 5M input tokens on Sonnet at $2/M = $10 > the medium tier's $4 budget.
        Task task = runningTask(taskService, tasks, "[[fake:expensive]]");
        RunResult result = implement(task);

        assertThat(result.reason()).isEqualTo("budget");
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isEqualTo(1);
    }

    @Test
    void timeoutKill() {
        Task task = runningTask(taskService, tasks, "[[fake:hang]]");
        long started = System.nanoTime();
        RunResult result = runner.execute(start(runs, props, task.id(), RunKind.CI_FIX, "ci_fixer", "{}"));

        assertThat(result.reason()).isEqualTo("timeout");
        assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(40));
    }

    @Test
    void planLimitPausesUntilReset() {
        Task task = runningTask(taskService, tasks, "[[fake:plan-limit]]");
        RunResult result = implement(task);

        assertThat(result.reason()).isEqualTo("plan_limit");
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).as("not the model's fault").isZero();
        assertThat(pause.current()).hasValueSatisfying(p -> {
            assertThat(p.reason()).isEqualTo("plan_limit");
            assertThat(p.until().atZoneSameInstant(ZoneId.of("Europe/Berlin")).getHour()).isEqualTo(17);
            assertThat(p.until()).isAfter(OffsetDateTime.now());
        });
    }

    @Test
    void rejectedTokenPausesOpenEndedAndApiRetriesArePersisted() {
        Task task = runningTask(taskService, tasks, "[[fake:auth-failed]]");
        RunResult result = implement(task);

        assertThat(result.reason()).isEqualTo("auth_failed");
        assertThat(pause.current()).hasValueSatisfying(p -> {
            assertThat(p.reason()).isEqualTo("auth_failed");
            assertThat(p.until()).isNull();
        });
        assertThat(runEventTypes(runEvents, result.runId()).stream().filter("api_retry"::equals)).hasSize(2);
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isZero();
    }

    @Test
    void leaseIsHeartbeated() throws Exception {
        Task task = runningTask(taskService, tasks, "[[fake:hang]]");
        Run run = start(runs, props, task.id(), RunKind.CI_FIX, "ci_fixer", "{}");
        OffsetDateTime first = run.leaseExpiresAt();
        CompletableFuture<RunResult> running = CompletableFuture.supplyAsync(() -> runner.execute(run));

        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        OffsetDateTime later = first;
        while (!later.isAfter(first) && System.nanoTime() < deadline) {
            Thread.sleep(200);
            later = runs.findById(run.id()).orElseThrow().leaseExpiresAt();
        }
        assertThat(later).as("the lease moves forward while the run is alive").isAfter(first);
        assertThat(running.get(60, TimeUnit.SECONDS).reason()).isEqualTo("timeout");
    }

    @Test
    void retryReusesTheWorktree() {
        Task task = runningTask(taskService, tasks, "Print hello.");
        RunResult first = runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer",
                "{\"feedback\":[{\"source\":\"test\",\"summary\":\"[[fake:report-failed]]\"}]}"));
        assertThat(first.reason()).isEqualTo("reported_failed");

        RunResult second = implement(task);

        assertThat(second.status()).isEqualTo(RunStatus.SUCCEEDED);
        Path wt = worktrees.resolve(task.id());
        assertThat(git(wt, "rev-list", "--count", "main..HEAD")).as("both runs' commits are on the branch").isEqualTo("2");
        String prompt = readPrompt(second);
        assertThat(prompt).contains("## Previous attempts").contains("reported_failed")
                .contains("The tests would not pass.");
    }

    @Test
    void fixRunPromptCarriesTheFeedback() throws Exception {
        Task task = runningTask(taskService, tasks, "Print hello.");
        RunResult result = runner.execute(start(runs, props, task.id(), RunKind.CI_FIX, "ci_fixer",
                "{\"feedback\":[{\"source\":\"tests\",\"summary\":\"./gradlew test failed\",\"detail\":\"FooTest > bar FAILED\"}]}"));

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(readPrompt(result)).contains("## Feedback to address").contains("./gradlew test failed")
                .contains("FooTest > bar FAILED");
        assertThat(worktrees.resolve(task.id()).resolve("fixed.txt")).exists();
    }

    @Test
    void earlierFailuresEscalateTheTier() {
        Task task = runningTask(taskService, tasks, "Print hello.");
        String fail = "{\"feedback\":[{\"source\":\"test\",\"summary\":\"[[fake:error]]\"}]}";
        runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer", fail));
        runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer", fail));

        RunResult third = implement(task);

        Run row = runs.findById(third.runId()).orElseThrow();
        assertThat(row.tier()).as("two failures move implementer M from medium to large").isEqualTo("large");
        assertThat(row.model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void cancelKillsTheRunningProcess() throws Exception {
        Task task = runningTask(taskService, tasks, "[[fake:hang]]");
        CompletableFuture<RunResult> running = CompletableFuture.supplyAsync(() -> implement(task));

        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (!runner.cancel(task.id())) {
            assertThat(System.nanoTime()).as("run never became cancellable").isLessThan(deadline);
            Thread.sleep(100);
        }
        RunResult result = running.get(30, TimeUnit.SECONDS);

        assertThat(result.status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(result.reason()).isEqualTo("cancelled");
        assertThat(runs.findById(result.runId()).orElseThrow().status()).isEqualTo(RunStatus.CANCELLED);
        assertThat(runner.cancel(task.id())).as("nothing left to cancel").isFalse();
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isZero();
    }

    @Test
    void credentialsAndRunTokenAreRedacted() throws Exception {
        Task task = runningTask(taskService, tasks, "[[fake:leak-env]]");
        RunResult result = implement(task);

        String token = Files.readString(RUN_ROOT.resolve(result.runId() + "/claude-config/fake-token.txt")).strip();
        assertThat(token).isNotBlank();
        String events = runEventsText(runEvents, result.runId());
        String stream = Files.readString(RUN_ROOT.resolve(result.runId() + "/stream.jsonl"));
        String stderr = Files.readString(RUN_ROOT.resolve(result.runId() + "/stderr.log"));
        String outcome = runs.findById(result.runId()).orElseThrow().outcome().data();
        for (String text : new String[]{events, stream, stderr, outcome}) {
            assertThat(text).doesNotContain("test-plan-token").doesNotContain(token);
        }
        assertThat(events).contains("***");
        assertThat(result.lastAssistantText()).doesNotContain(token);
    }

    @Test
    void runsThatTheReaperTookAreLeftAlone() {
        Task task = runningTask(taskService, tasks, "Print hello.");
        Run run = start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer", "{}");
        runs.finish(run.id(), RunStatus.FAILED, "lease_expired", java.math.BigDecimal.ZERO, 0, 0, 0, null);

        RunResult result = runner.execute(run);

        assertThat(runs.findById(run.id()).orElseThrow().error()).isEqualTo("lease_expired");
        assertThat(result.reason()).isEqualTo("lease_expired");
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isZero();
    }

    private String readPrompt(RunResult result) {
        try {
            return Files.readString(RUN_ROOT.resolve(result.runId() + "/claude-config/fake-prompt.txt"));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unused")
    private static UUID id(Task t) {
        return t.id();
    }
}
