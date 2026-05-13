package com.ai.orange.task;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TaskRepositoryConcurrencyIT {

    @Autowired private TaskRepository taskRepository;
    @Autowired private TaskService taskService;
    @Autowired private DSLContext dsl;

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void concurrent_workers_never_claim_the_same_task() throws InterruptedException {
        // 20 ready tasks, 4 workers each grabbing greedily. The total number
        // of distinct claimed task IDs must equal 20 (no duplicates), and
        // all 20 must be claimed (no skips).
        int taskCount = 20;
        int workerCount = 4;

        List<TaskDef> defs = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            defs.add(new TaskDef("t" + i, "Task " + i, null, "dev", null, null, null));
        }
        List<Task> created = taskService.createGraph(defs, List.of());
        Set<UUID> seedIds = new java.util.HashSet<>();
        for (Task t : created) seedIds.add(t.id());
        assertThat(created).allSatisfy(t -> assertThat(t.status()).isEqualTo(TaskStatus.READY));

        Set<UUID> allClaimed = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicateClaims = new AtomicInteger();

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workerCount);

        for (int w = 0; w < workerCount; w++) {
            String workerId = "worker-" + w;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    while (true) {
                        Optional<Task> claimed = taskRepository.claimNextReady("dev", workerId);
                        if (claimed.isEmpty()) break;
                        if (!allClaimed.add(claimed.get().id())) {
                            duplicateClaims.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();

        assertThat(duplicateClaims.get())
                .as("no task should ever be claimed by more than one worker")
                .isZero();
        assertThat(allClaimed)
                .as("every seeded ready task should have been claimed exactly once")
                .hasSize(taskCount)
                .containsExactlyInAnyOrderElementsOf(seedIds);

        // Every task in DB should now be in_progress with a claimed_by attribution.
        int inProgress = dsl.fetchCount(TASKS, TASKS.STATUS.eq(TaskStatus.IN_PROGRESS.dbValue()));
        assertThat(inProgress).isEqualTo(taskCount);
    }

    @Test
    void claim_returns_empty_when_no_ready_tasks_match_role() {
        var def = new TaskDef("only-tester", "QA", null, "tester", null, null, null);
        taskService.createGraph(List.of(def), List.of());

        Optional<Task> claimed = taskRepository.claimNextReady("dev", "worker-A");
        assertThat(claimed).isEmpty();

        Optional<Task> claimedTester = taskRepository.claimNextReady("tester", "worker-A");
        assertThat(claimedTester).isPresent();
    }
}
