package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.run.RunTestSupport.runningTask;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs nobody is supervising any more end as failed, without counting against their tasks. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RunReaperIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("reaper-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("reaper-it-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
    }

    @Autowired RunReaper reaper;
    @Autowired TaskReactor reactor;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired SchedulerPause pause;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        pause.resume();
        dsl.deleteFrom(RUNS).execute();
    }

    @Test
    void expiredLeaseFailsWithoutCountingFailures() {
        Task task = runningTask(taskService, tasks, "x");
        Run run = runs.insertRunning(RunKind.IMPLEMENT, "implementer", task.id(), props.workerId(),
                Duration.ofSeconds(-5), JSONB.valueOf("{}"));
        Run live = runs.insertRunning(RunKind.IMPLEMENT, "implementer", runningTask(taskService, tasks, "y").id(),
                props.workerId(), Duration.ofMinutes(5), JSONB.valueOf("{}"));

        reaper.reapExpired();

        Run reaped = runs.findById(run.id()).orElseThrow();
        assertThat(reaped.status()).isEqualTo(RunStatus.FAILED);
        assertThat(reaped.error()).isEqualTo("lease_expired");
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isZero();
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(runs.findById(live.id()).orElseThrow().status()).as("a live lease is left alone")
                .isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void orphansOfAPreviousInstanceAreKilledAndFailed() throws Exception {
        Task task = runningTask(taskService, tasks, "x");
        Process orphan = new ProcessBuilder("sleep", "600").start();
        try {
            Run run = runs.insertRunning(RunKind.IMPLEMENT, "implementer", task.id(), "previous-instance",
                    Duration.ofMinutes(5), JSONB.valueOf("{}"));
            runs.recordPid(run.id(), orphan.pid());

            reaper.failOrphans();

            assertThat(orphan.waitFor(10, TimeUnit.SECONDS)).as("the orphaned process was killed").isTrue();
            Run failed = runs.findById(run.id()).orElseThrow();
            assertThat(failed.status()).isEqualTo(RunStatus.FAILED);
            assertThat(failed.error()).isEqualTo("coordinator_restart");
            assertThat(tasks.findById(task.id()).orElseThrow().failures()).isZero();

            reactor.react(task.id());
            assertThat(runs.findByTask(task.id())).filteredOn(r -> r.status() == RunStatus.QUEUED)
                    .singleElement().satisfies(r -> assertThat(r.kind()).isEqualTo(RunKind.IMPLEMENT));
        } finally {
            orphan.destroyForcibly();
        }
    }

    @Test
    void aReusedPidIsNotKilled() throws Exception {
        Task task = runningTask(taskService, tasks, "x");
        Process unrelated = new ProcessBuilder("sleep", "600").start();
        try {
            Run run = runs.insertRunning(RunKind.IMPLEMENT, "implementer", task.id(), "previous-instance",
                    Duration.ofMinutes(5), JSONB.valueOf("{}"));
            runs.recordPid(run.id(), unrelated.pid());
            // The run started an hour ago, so a process that started just now cannot be its harness.
            dsl.update(RUNS).set(RUNS.STARTED_AT, OffsetDateTime.now().minusHours(1)).where(RUNS.ID.eq(run.id())).execute();

            reaper.failOrphans();

            assertThat(unrelated.isAlive()).isTrue();
            assertThat(runs.findById(run.id()).orElseThrow().error()).isEqualTo("coordinator_restart");
        } finally {
            unrelated.destroyForcibly();
        }
    }

    @Test
    void anOrphanedEvidenceCommandIsKilled() throws Exception {
        Task task = runningTask(taskService, tasks, "x");
        Run run = runs.insertRunning(RunKind.VERIFY, "orange", task.id(), "previous-instance", Duration.ofMinutes(5),
                JSONB.valueOf("{}"));
        // A verify run's commands start one after another, long after the run itself started.
        dsl.update(RUNS).set(RUNS.STARTED_AT, OffsetDateTime.now().minusMinutes(3)).where(RUNS.ID.eq(run.id())).execute();
        Process command = new ProcessBuilder("sleep", "600").start();
        try {
            runs.recordPid(run.id(), command.pid());

            reaper.failOrphans();

            assertThat(command.waitFor(10, TimeUnit.SECONDS)).as("the orphaned evidence command was killed").isTrue();
            assertThat(runs.findById(run.id()).orElseThrow().error()).isEqualTo("coordinator_restart");
        } finally {
            command.destroyForcibly();
        }
    }

    @Test
    void ownRunsAreNotOrphans() {
        Task task = runningTask(taskService, tasks, "x");
        Run run = runs.insertRunning(RunKind.IMPLEMENT, "implementer", task.id(), props.workerId(),
                Duration.ofMinutes(5), JSONB.valueOf("{}"));

        reaper.failOrphans();

        assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo(RunStatus.RUNNING);
    }
}
