package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.run.RunTestSupport.headInput;
import static com.ai.orange.run.RunTestSupport.runningTask;
import static com.ai.orange.run.RunTestSupport.taskEventTypes;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.json.Json;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The reactor turns each task's state into its next run, exactly once. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TaskReactorIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("reactor-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("reactor-it-wt-");
    static final String HEAD = "1111111111111111111111111111111111111111";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
    }

    @Autowired TaskReactor reactor;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired EvidenceRepository evidence;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository taskEvents;
    @Autowired SchedulerPause pause;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        pause.resume();
        dsl.deleteFrom(RUNS).execute();
    }

    Task readyTask() {
        return taskService.createGraph(List.of(new TaskDef("reactor-" + UUID.randomUUID(), "React to me", "x",
                "implementer", 70, null)), List.of()).get(0);
    }

    /** A run of {@code kind} that already ran and finished with {@code outcome}. */
    void finishedRun(UUID taskId, RunKind kind, String input, RunOutcome outcome, RunStatus status) {
        Run run = runs.insertRunning(kind, "orange", taskId, props.workerId(), props.leaseTtl(), JSONB.valueOf(input));
        if (outcome != null) runs.recordOutcome(run.id(), outcome.toJsonb());
        runs.finish(run.id(), status, status == RunStatus.FAILED ? "timeout" : null, BigDecimal.ZERO, 0, 0, 0, null);
    }

    /** Claims the only queued run of {@code kind} and finishes it, as an executor would. */
    void complete(RunKind kind, RunOutcome outcome) {
        Run run = runs.claimNext(kind, props.workerId(), props.leaseTtl()).orElseThrow();
        runs.recordOutcome(run.id(), outcome.toJsonb());
        runs.finish(run.id(), RunStatus.SUCCEEDED, null, BigDecimal.ZERO, 0, 0, 0, null);
    }

    Task verifying(Task t, String head) {
        tasks.transitionStatus(t.id(), TaskStatus.READY, TaskStatus.RUNNING);
        tasks.recordHead(t.id(), "orange/" + t.id(), head);
        tasks.transitionStatus(t.id(), TaskStatus.RUNNING, TaskStatus.VERIFYING);
        return tasks.findById(t.id()).orElseThrow();
    }

    Run onlyRun(UUID taskId) {
        List<Run> all = runs.findByTask(taskId).stream().filter(r -> r.status() == RunStatus.QUEUED).toList();
        assertThat(all).hasSize(1);
        return all.getFirst();
    }

    static JsonNode json(JSONB value) throws Exception {
        return Json.mapper().readTree(value.data());
    }

    @Test
    void readyTaskGetsAnImplementRun() {
        Task task = readyTask();

        reactor.react(task.id());

        Run run = onlyRun(task.id());
        assertThat(run.kind()).isEqualTo(RunKind.IMPLEMENT);
        assertThat(run.role()).isEqualTo("implementer");
        assertThat(run.priority()).isEqualTo(70);
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(taskEventTypes(taskEvents, task.id())).contains("run_queued");
    }

    @Test
    void concurrentReactsQueueExactlyOneRun() throws Exception {
        Task task = readyTask();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    reactor.react(task.id());
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(runs.findByTask(task.id())).hasSize(1);
        assertThat(taskEventTypes(taskEvents, task.id()).stream().filter("run_queued"::equals)).hasSize(1);
    }

    @Test
    void verifyGapsQueueAFixWithTheFeedback() throws Exception {
        Task task = runningTask(taskService, tasks, "x");
        finishedRun(task.id(), RunKind.VERIFY, headInput(HEAD), new RunOutcome.Verification(false, false,
                List.of(new EvidenceGap("tests", "./gradlew test", 1, "FooTest > bar FAILED"))), RunStatus.SUCCEEDED);

        reactor.react(task.id());

        Run fix = onlyRun(task.id());
        assertThat(fix.kind()).isEqualTo(RunKind.CI_FIX);
        assertThat(fix.role()).isEqualTo("ci_fixer");
        JsonNode item = json(fix.input()).path("feedback").get(0);
        assertThat(item.path("summary").asText()).contains("./gradlew test");
        assertThat(item.path("detail").asText()).isEqualTo("FooTest > bar FAILED");
    }

    @Test
    void reviewGapsQueueReworkWithTheGaps() throws Exception {
        Task task = runningTask(taskService, tasks, "x");
        finishedRun(task.id(), RunKind.DIFF_REVIEW, headInput(HEAD), new RunOutcome.Review("gaps", "One problem",
                List.of(new ReviewGap("README.md", 1, "The heading has a typo."))), RunStatus.SUCCEEDED);

        reactor.react(task.id());

        Run rework = onlyRun(task.id());
        assertThat(rework.kind()).isEqualTo(RunKind.IMPLEMENT);
        assertThat(rework.role()).isEqualTo("implementer");
        assertThat(json(rework.input()).path("feedback").get(0).path("summary").asText())
                .isEqualTo("README.md:1: The heading has a typo.");
    }

    @Test
    void verifyingRunsVerifyThenDiffReview() throws Exception {
        Task task = verifying(readyTask(), HEAD);

        reactor.react(task.id());
        Run verify = onlyRun(task.id());
        assertThat(verify.kind()).isEqualTo(RunKind.VERIFY);
        assertThat(verify.role()).isEqualTo("orange");
        assertThat(json(verify.input()).path("head_sha").asText()).isEqualTo(HEAD);

        complete(RunKind.VERIFY, new RunOutcome.Verification(false, true, List.of()));
        reactor.react(task.id());
        Run review = onlyRun(task.id());
        assertThat(review.kind()).isEqualTo(RunKind.DIFF_REVIEW);
        assertThat(review.role()).isEqualTo("reviewer");
        assertThat(json(review.input()).path("head_sha").asText()).isEqualTo(HEAD);

        // The review's own post-run step would verify the task; if the coordinator died between
        // recording the evidence and the transition, the reactor finishes the job.
        complete(RunKind.DIFF_REVIEW, new RunOutcome.Review("pass", "fine", List.of()));
        evidence.record(task.id(), review.id(), HEAD, "diff_review", null, null, "fine", true);
        reactor.react(task.id());
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.VERIFIED);
    }

    @Test
    void verifiedGetsAnIntegrateRun() throws Exception {
        Task task = verifying(readyTask(), HEAD);
        tasks.transitionStatus(task.id(), TaskStatus.VERIFYING, TaskStatus.VERIFIED);

        reactor.react(task.id());

        Run integrate = onlyRun(task.id());
        assertThat(integrate.kind()).isEqualTo(RunKind.INTEGRATE);
        assertThat(integrate.role()).isEqualTo("orange");
        assertThat(json(integrate.input()).path("head_sha").asText()).isEqualTo(HEAD);
    }

    @Test
    void thirdFailureMovesToNeedsHuman() {
        Task task = runningTask(taskService, tasks, "x");
        for (int i = 0; i < 3; i++) tasks.incrementFailures(task.id());

        reactor.react(task.id());

        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.NEEDS_HUMAN);
        assertThat(runs.findByTask(task.id())).isEmpty();
        assertThat(taskEvents.findByTaskSince(task.id(), 0, 100)).anySatisfy(e -> {
            assertThat(e.eventType()).isEqualTo("needs_human");
            assertThat(e.payload().data()).contains("failures");
        });
    }

    @Test
    void pausedQueuesNothing() {
        Task task = readyTask();
        pause.pause("operator", null);

        reactor.react(task.id());
        reactor.sweep();

        assertThat(runs.findByTask(task.id())).isEmpty();
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.READY);
    }

    @Test
    void sweepReactsToEveryActiveTask() {
        Task a = readyTask();
        Task b = readyTask();

        reactor.sweep();

        assertThat(runs.findByTask(a.id())).hasSize(1);
        assertThat(runs.findByTask(b.id())).hasSize(1);
    }
}
