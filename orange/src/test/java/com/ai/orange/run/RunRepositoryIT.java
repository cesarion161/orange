package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RunRepositoryIT {

    static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired RunRepository runs;
    @Autowired RunEventRepository runEvents;
    @Autowired TaskService taskService;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        dsl.deleteFrom(RUNS).execute();
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void oneActiveRunPerTask() {
        UUID task = newTask();
        Run implement = runs.queue(task, RunKind.IMPLEMENT, "implementer", 100, json("{}")).orElseThrow();
        assertThat(implement.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(implement.attempt()).isEqualTo(1);

        assertThat(runs.queue(task, RunKind.DIFF_REVIEW, "reviewer", 100, json("{}")))
                .as("a second active run on the same task").isEmpty();

        Run claimed = runs.claimNext(RunKind.IMPLEMENT, "w1", LEASE).orElseThrow();
        assertThat(runs.queue(task, RunKind.DIFF_REVIEW, "reviewer", 100, json("{}"))).as("still running").isEmpty();
        assertThat(runs.finish(claimed.id(), RunStatus.SUCCEEDED, null, BigDecimal.ONE, 10, 5, 2, "s")).isTrue();

        Run review = runs.queue(task, RunKind.DIFF_REVIEW, "reviewer", 100, json("{\"head_sha\":\"abc\"}")).orElseThrow();
        assertThat(review.attempt()).as("attempt counts per kind").isEqualTo(1);
        runs.claimNext(RunKind.DIFF_REVIEW, "w1", LEASE).orElseThrow();
        runs.finish(review.id(), RunStatus.FAILED, "no_outcome", BigDecimal.ZERO, 0, 0, 0, null);

        assertThat(runs.queue(task, RunKind.IMPLEMENT, "implementer", 100, json("{}")).orElseThrow().attempt())
                .isEqualTo(2);
        assertThat(runs.findActiveByTask(task)).map(Run::kind).hasValue(RunKind.IMPLEMENT);
    }

    @Test
    void concurrentClaimsNeverShareARun() throws Exception {
        for (int i = 0; i < 20; i++) {
            runs.queue(newTask(), RunKind.IMPLEMENT, "implementer", 100, json("{}")).orElseThrow();
        }
        Set<UUID> claimed = ConcurrentHashMap.newKeySet();
        List<UUID> all = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < 8; t++) {
                String worker = "w" + t;
                futures.add(pool.submit(() -> {
                    Optional<Run> next;
                    while ((next = runs.claimNext(RunKind.IMPLEMENT, worker, LEASE)).isPresent()) {
                        claimed.add(next.get().id());
                        all.add(next.get().id());
                    }
                }));
            }
            for (Future<?> f : futures) f.get();
        } finally {
            pool.shutdownNow();
        }
        assertThat(all).hasSize(20);
        assertThat(claimed).hasSize(20);
        assertThat(runs.countByStatus(RunKind.IMPLEMENT, RunStatus.RUNNING)).isEqualTo(20);
    }

    @Test
    void claimOrdersByPriorityThenQueueTime() throws Exception {
        Run late = runs.queue(newTask(), RunKind.IMPLEMENT, "implementer", 100, json("{}")).orElseThrow();
        Thread.sleep(5);
        Run later = runs.queue(newTask(), RunKind.IMPLEMENT, "implementer", 100, json("{}")).orElseThrow();
        Run urgent = runs.queue(newTask(), RunKind.IMPLEMENT, "implementer", 10, json("{}")).orElseThrow();
        Run otherKind = runs.queue(newTask(), RunKind.DIFF_REVIEW, "reviewer", 1, json("{}")).orElseThrow();

        assertThat(runs.claimNext(RunKind.IMPLEMENT, "w", LEASE)).map(Run::id).hasValue(urgent.id());
        assertThat(runs.claimNext(RunKind.IMPLEMENT, "w", LEASE)).map(Run::id).hasValue(late.id());
        assertThat(runs.claimNext(RunKind.IMPLEMENT, "w", LEASE)).map(Run::id).hasValue(later.id());
        assertThat(runs.claimNext(RunKind.IMPLEMENT, "w", LEASE)).isEmpty();
        assertThat(runs.findById(otherKind.id()).orElseThrow().status()).isEqualTo(RunStatus.QUEUED);
    }

    @Test
    void heartbeatOnlyExtendsTheOwnersLease() {
        runs.queue(newTask(), RunKind.IMPLEMENT, "implementer", 100, json("{}")).orElseThrow();
        Run run = runs.claimNext(RunKind.IMPLEMENT, "owner", Duration.ofSeconds(30)).orElseThrow();
        OffsetDateTime first = run.leaseExpiresAt();
        assertThat(run.workerId()).isEqualTo("owner");
        assertThat(run.startedAt()).isNotNull();

        assertThat(runs.heartbeat(run.id(), "someone-else", LEASE)).isFalse();
        assertThat(runs.findById(run.id()).orElseThrow().leaseExpiresAt()).isEqualTo(first);

        assertThat(runs.heartbeat(run.id(), "owner", LEASE)).isTrue();
        assertThat(runs.findById(run.id()).orElseThrow().leaseExpiresAt()).isAfter(first);

        runs.finish(run.id(), RunStatus.SUCCEEDED, null, BigDecimal.ZERO, 0, 0, 0, null);
        assertThat(runs.heartbeat(run.id(), "owner", LEASE)).as("finished runs hold no lease").isFalse();
    }

    @Test
    void outcomeIsOnlyRecordedWhileRunning() {
        Run queued = runs.queue(newTask(), RunKind.IMPLEMENT, "implementer", 100, json("{}")).orElseThrow();
        assertThat(runs.recordOutcome(queued.id(), json("{\"type\":\"result\",\"status\":\"done\"}"))).isFalse();

        Run run = runs.claimNext(RunKind.IMPLEMENT, "w", LEASE).orElseThrow();
        assertThat(runs.recordOutcome(run.id(), json("{\"type\":\"result\",\"status\":\"failed\"}"))).isTrue();
        assertThat(runs.recordOutcome(run.id(), json("{\"type\":\"result\",\"status\":\"done\"}"))).isTrue();
        assertThat(runs.findById(run.id()).orElseThrow().outcome().data()).contains("done");

        runs.finish(run.id(), RunStatus.SUCCEEDED, null, BigDecimal.ZERO, 0, 0, 0, null);
        assertThat(runs.recordOutcome(run.id(), json("{\"type\":\"result\",\"status\":\"failed\"}"))).isFalse();
        assertThat(runs.findById(run.id()).orElseThrow().outcome().data()).contains("done");
        assertThat(runs.finish(run.id(), RunStatus.FAILED, "x", BigDecimal.ZERO, 0, 0, 0, null))
                .as("finish only from running").isFalse();
    }

    @Test
    void expiredLeasesAndForeignRunsAreFound() {
        runs.queue(newTask(), RunKind.IMPLEMENT, "implementer", 100, json("{}")).orElseThrow();
        runs.queue(newTask(), RunKind.IMPLEMENT, "implementer", 100, json("{}")).orElseThrow();
        Run mine = runs.claimNext(RunKind.IMPLEMENT, "me", LEASE).orElseThrow();
        Run old = runs.claimNext(RunKind.IMPLEMENT, "old-instance", Duration.ofSeconds(-60)).orElseThrow();

        assertThat(runs.findExpiredLeases(OffsetDateTime.now())).extracting(Run::id).containsExactly(old.id());
        assertThat(runs.findRunningNotOwnedBy("me")).extracting(Run::id).containsExactly(old.id());

        runs.recordPid(mine.id(), 4242L);
        runs.recordRoute(mine.id(), "medium", "claude-sonnet-5-5", "high", "claude-code", "subscription",
                new BigDecimal("4"), null, "hash-1", "/tmp/run/stream.jsonl");
        Run reloaded = runs.findById(mine.id()).orElseThrow();
        assertThat(reloaded.pid()).isEqualTo(4242L);
        assertThat(reloaded.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(reloaded.auth()).isEqualTo("subscription");
        assertThat(runs.findRunningByTokenHash("hash-1")).map(Run::id).hasValue(mine.id());
        assertThat(runs.findRunningByTokenHash("nope")).isEmpty();

        runs.finish(mine.id(), RunStatus.SUCCEEDED, null, BigDecimal.ZERO, 0, 0, 0, null);
        assertThat(runs.findRunningByTokenHash("hash-1")).as("only running runs resolve").isEmpty();
    }

    @Test
    void planRunsHaveNoTask() {
        Run plan = runs.insertRunning(RunKind.PLAN, "planner", null, "w", LEASE, json("{}"));
        assertThat(plan.taskId()).isNull();
        assertThat(plan.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(plan.leaseExpiresAt()).isAfter(OffsetDateTime.now());
        // Several task-less runs may be active at once: the one-active rule is per task.
        assertThat(runs.insertRunning(RunKind.PLAN, "planner", null, "w", LEASE, json("{}")).id())
                .isNotEqualTo(plan.id());
    }

    @Test
    void cancelQueuedCancelsOnlyQueuedRuns() {
        UUID task = newTask();
        runs.queue(task, RunKind.IMPLEMENT, "implementer", 100, json("{}")).orElseThrow();
        assertThat(runs.cancelQueued(task)).isEqualTo(1);
        assertThat(runs.findByTask(task)).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(RunStatus.CANCELLED));
        assertThat(runs.findActiveByTask(task)).isEmpty();
    }

    @Test
    void runEventsPageBySeq() {
        Run run = runs.insertRunning(RunKind.PLAN, "planner", null, "w", LEASE, json("{}"));
        for (int seq = 1; seq <= 5; seq++) {
            runEvents.append(run.id(), seq, "assistant", json("{\"text\":\"m" + seq + "\"}"));
        }
        assertThat(runEvents.findByRunSince(run.id(), 2, 2)).extracting(RunEventRow::seq).containsExactly(3, 4);
        assertThat(runEvents.findByRunSince(run.id(), 0, 10_000)).hasSize(5);
    }

    private UUID newTask() {
        return taskService.createGraph(
                List.of(new TaskDef("t-" + UUID.randomUUID(), "T", null, "implementer", null, null)),
                List.of()).get(0).id();
    }

    private static JSONB json(String s) {
        return JSONB.valueOf(s);
    }
}
