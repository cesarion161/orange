package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The scheduler claims queued runs up to each kind's slots and the global cap, and runs them. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RunSchedulerIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("scheduler-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("scheduler-it-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
        r.add("orange.runs.slots.implement", () -> "2");
        r.add("orange.runs.slots.judge", () -> "1");
    }

    @MockitoBean WorkerRunner worker;
    @MockitoBean EvidenceRunner verifier;
    @MockitoBean Integrator integrator;
    @Autowired RunScheduler scheduler;
    @Autowired RunRepository runs;
    @Autowired TaskService taskService;
    @Autowired SchedulerPause pause;
    @Autowired DSLContext dsl;

    CountDownLatch release;
    final AtomicInteger executing = new AtomicInteger();

    @BeforeEach
    void setUp() {
        pause.resume();
        dsl.deleteFrom(RUNS).execute();
        release = new CountDownLatch(1);
        executing.set(0);
        reset(worker, verifier, integrator);
        when(worker.kinds()).thenReturn(Set.of(RunKind.PLAN, RunKind.IMPLEMENT, RunKind.CI_FIX, RunKind.DIFF_REVIEW));
        when(verifier.kinds()).thenReturn(Set.of(RunKind.VERIFY));
        when(integrator.kinds()).thenReturn(Set.of(RunKind.INTEGRATE));
        for (RunExecutor ex : List.of(worker, verifier, integrator)) {
            when(ex.execute(any())).thenAnswer(inv -> {
                executing.incrementAndGet();
                release.await(30, TimeUnit.SECONDS);
                return null;
            });
        }
    }

    @AfterEach
    void releaseExecutors() {
        release.countDown();
    }

    Run queue(RunKind kind) {
        Task task = taskService.createGraph(List.of(new TaskDef("sched-" + UUID.randomUUID(), "T", "x",
                "implementer", null, null)), List.of()).get(0);
        return runs.queue(task.id(), kind, "implementer", 100, JSONB.valueOf("{}")).orElseThrow();
    }

    int count(RunKind kind, RunStatus status) {
        return runs.countByStatus(kind, status);
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("condition not reached in time").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    @Test
    void slotsBoundConcurrency() throws Exception {
        for (int i = 0; i < 5; i++) queue(RunKind.IMPLEMENT);

        scheduler.fillSlots();
        await(() -> executing.get() == 2);
        scheduler.fillSlots();

        assertThat(count(RunKind.IMPLEMENT, RunStatus.RUNNING)).isEqualTo(2);
        assertThat(count(RunKind.IMPLEMENT, RunStatus.QUEUED)).isEqualTo(3);
        Thread.sleep(200);
        assertThat(executing.get()).isEqualTo(2);
    }

    @Test
    void integrationsAreSerialized() throws Exception {
        for (int i = 0; i < 3; i++) queue(RunKind.INTEGRATE);

        scheduler.fillSlots();
        await(() -> executing.get() == 1);
        scheduler.fillSlots();

        assertThat(count(RunKind.INTEGRATE, RunStatus.RUNNING)).isEqualTo(1);
        assertThat(count(RunKind.INTEGRATE, RunStatus.QUEUED)).isEqualTo(2);
    }

    @Test
    void kindsWithoutAnExecutorAreNeverClaimed() throws Exception {
        Run judge = queue(RunKind.JUDGE);

        scheduler.fillSlots();
        Thread.sleep(200);

        assertThat(runs.findById(judge.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void pausedStartsNothing() throws Exception {
        Run run = queue(RunKind.IMPLEMENT);
        pause.pause("operator", null);

        scheduler.fillSlots();
        Thread.sleep(200);

        assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
        assertThat(executing.get()).isZero();
    }

    @Test
    void anExecutorThatThrowsFailsItsRunAndFreesTheSlot() throws Exception {
        // doThrow, not when(): calling execute() to stub it would run the latch-blocking answer.
        doThrow(new IllegalStateException("boom")).when(worker).execute(any());
        Run run = queue(RunKind.IMPLEMENT);

        scheduler.fillSlots();
        await(() -> runs.findById(run.id()).orElseThrow().status() == RunStatus.FAILED);

        assertThat(runs.findById(run.id()).orElseThrow().error()).isEqualTo("start_failed");
        await(() -> scheduler.inFlight(RunKind.IMPLEMENT) == 0);
        assertThat(pause.current()).as("the same failure would recur at once")
                .hasValueSatisfying(p -> assertThat(p.reason()).isEqualTo("start_failed"));
    }
}
