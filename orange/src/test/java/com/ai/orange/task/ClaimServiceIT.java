package com.ai.orange.task;

import static com.ai.orange.db.jooq.Tables.AGENT_RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_CLAIMS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.ClaimService.ClaimedTask;
import com.ai.orange.task.ClaimService.FailResult;
import com.ai.orange.task.ClaimService.HeartbeatResult;
import com.ai.orange.task.ClaimService.ReleaseResult;
import java.time.OffsetDateTime;
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
class ClaimServiceIT {

    @Autowired private ClaimService claimService;
    @Autowired private TaskService taskService;
    @Autowired private TaskRepository tasks;
    @Autowired private TaskClaimRepository claimRepo;
    @Autowired private TaskEdgeRepository edges;
    @Autowired private ClaimReaper reaper;
    @Autowired private DSLContext dsl;

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_CLAIMS).execute();
        dsl.deleteFrom(AGENT_RUNS).execute();
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void claim_next_transitions_task_to_in_progress_and_issues_token() {
        Task t = taskService.createGraph(
                List.of(new TaskDef("t1", "Build feature", null, "dev", null, null, null)),
                List.of()).get(0);

        Optional<ClaimedTask> claimed = claimService.claimNext("dev", "worker-A");
        assertThat(claimed).isPresent();
        ClaimedTask c = claimed.get();
        assertThat(c.task().id()).isEqualTo(t.id());
        assertThat(c.claimToken()).isNotNull();
        assertThat(c.attempt()).isEqualTo(1);
        assertThat(c.cwd()).isNotBlank();
        assertThat(c.prompt()).contains("Build feature");
        assertThat(c.leaseExpiresAt()).isAfter(OffsetDateTime.now());

        Task refreshed = tasks.findById(t.id()).orElseThrow();
        assertThat(refreshed.status()).isEqualTo(TaskStatus.IN_PROGRESS);

        TaskClaim row = claimRepo.findByToken(c.claimToken()).orElseThrow();
        assertThat(row.workerId()).isEqualTo("worker-A");
    }

    @Test
    void claim_next_returns_empty_when_no_ready_tasks() {
        assertThat(claimService.claimNext("dev", "worker-A")).isEmpty();
    }

    @Test
    void concurrent_claims_never_double_claim() throws InterruptedException {
        int taskCount = 12;
        int workerCount = 4;
        List<TaskDef> defs = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            defs.add(new TaskDef("t" + i, "Task " + i, null, "dev", null, null, null));
        }
        taskService.createGraph(defs, List.of());

        Set<UUID> claimedIds = ConcurrentHashMap.newKeySet();
        AtomicInteger dupes = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workerCount);
        for (int w = 0; w < workerCount; w++) {
            String workerId = "worker-" + w;
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    while (true) {
                        Optional<ClaimedTask> got = claimService.claimNext("dev", workerId);
                        if (got.isEmpty()) break;
                        if (!claimedIds.add(got.get().task().id())) {
                            dupes.incrementAndGet();
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

        assertThat(dupes.get()).isZero();
        assertThat(claimedIds).hasSize(taskCount);
        assertThat(dsl.fetchCount(TASK_CLAIMS)).isEqualTo(taskCount);
    }

    @Test
    void heartbeat_extends_lease() {
        Task t = taskService.createGraph(
                List.of(new TaskDef("t1", "x", null, "dev", null, null, null)), List.of()).get(0);
        ClaimedTask c = claimService.claimNext("dev", "worker-A").orElseThrow();
        OffsetDateTime original = c.leaseExpiresAt();

        // Sleep a tick so the new expiry is observably later.
        try { Thread.sleep(20); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        HeartbeatResult hb = claimService.heartbeat(c.claimToken());
        assertThat(hb.alive()).isTrue();
        assertThat(hb.taskId()).isEqualTo(t.id());
        assertThat(hb.cancelRequested()).isFalse();
        assertThat(hb.leaseExpiresAt()).isAfter(original);
    }

    @Test
    void heartbeat_reports_expired_when_token_unknown() {
        HeartbeatResult hb = claimService.heartbeat(UUID.randomUUID());
        assertThat(hb.alive()).isFalse();
    }

    @Test
    void heartbeat_surfaces_cancel_request() {
        taskService.createGraph(
                List.of(new TaskDef("t1", "x", null, "dev", null, null, null)), List.of());
        ClaimedTask c = claimService.claimNext("dev", "worker-A").orElseThrow();
        claimRepo.requestCancel(c.task().id());
        HeartbeatResult hb = claimService.heartbeat(c.claimToken());
        assertThat(hb.alive()).isTrue();
        assertThat(hb.cancelRequested()).isTrue();
    }

    @Test
    void complete_transitions_to_pr_open_and_persists_artifacts() {
        Task t = taskService.createGraph(
                List.of(new TaskDef("t1", "build", null, "dev", null, null, null)),
                List.of()).get(0);
        ClaimedTask c = claimService.claimNext("dev", "worker-A").orElseThrow();

        claimService.complete(c.claimToken(), "done!",
                java.util.Map.of("notes", "everything green", "files", List.of("a.java")));

        Task refreshed = tasks.findById(t.id()).orElseThrow();
        assertThat(refreshed.status()).isEqualTo(TaskStatus.PR_OPEN);
        assertThat(refreshed.metadata().data())
                .contains("done!").contains("notes").contains("everything green");
        assertThat(claimRepo.findByToken(c.claimToken())).isEmpty();
    }

    @Test
    void fail_retryable_within_budget_bounces_back_to_ready() {
        Task t = taskService.createGraph(
                List.of(new TaskDef("t1", "x", null, "dev", null, null, null)), List.of()).get(0);
        ClaimedTask c = claimService.claimNext("dev", "worker-A").orElseThrow();

        FailResult r = claimService.fail(c.claimToken(), "blew up", true, 3);
        assertThat(r.ok()).isTrue();
        assertThat(r.newStatus()).isEqualTo(TaskStatus.READY);
        Task refreshed = tasks.findById(t.id()).orElseThrow();
        assertThat(refreshed.status()).isEqualTo(TaskStatus.READY);
    }

    @Test
    void fail_non_retryable_lands_at_failed() {
        Task t = taskService.createGraph(
                List.of(new TaskDef("t1", "x", null, "dev", null, null, null)), List.of()).get(0);
        ClaimedTask c = claimService.claimNext("dev", "worker-A").orElseThrow();

        FailResult r = claimService.fail(c.claimToken(), "fatal", false, 3);
        assertThat(r.ok()).isTrue();
        assertThat(r.newStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(tasks.findById(t.id()).orElseThrow().status()).isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void release_returns_task_to_ready_without_counting_against_budget() {
        Task t = taskService.createGraph(
                List.of(new TaskDef("t1", "x", null, "dev", null, null, null)), List.of()).get(0);
        ClaimedTask c = claimService.claimNext("dev", "worker-A").orElseThrow();
        // Burn one attempt to verify release doesn't push us past the budget.
        ReleaseResult r = claimService.release(c.claimToken(), "session restart");
        assertThat(r.ok()).isTrue();
        assertThat(tasks.findById(t.id()).orElseThrow().status()).isEqualTo(TaskStatus.READY);
        assertThat(claimRepo.findByToken(c.claimToken())).isEmpty();
    }

    @Test
    void reaper_bounces_expired_claim_back_to_ready() {
        Task t = taskService.createGraph(
                List.of(new TaskDef("t1", "x", null, "dev", null, null, null)), List.of()).get(0);
        ClaimedTask c = claimService.claimNext("dev", "worker-A").orElseThrow();
        // Backdate the lease so the reaper considers it expired.
        claimRepo.heartbeat(c.claimToken(), OffsetDateTime.now().minusMinutes(1));

        reaper.reap();

        assertThat(claimRepo.findByToken(c.claimToken())).isEmpty();
        assertThat(tasks.findById(t.id()).orElseThrow().status()).isEqualTo(TaskStatus.READY);
    }

    @Test
    void reaper_marks_failed_when_attempt_budget_burnt() {
        taskService.createGraph(
                List.of(new TaskDef("t1", "x", null, "dev", null, null, null)), List.of());
        ClaimedTask c = claimService.claimNext("dev", "worker-A").orElseThrow();
        // Pretend the worker has already retried twice; on the third reap we
        // exceed the 3-attempt cap.
        dsl.update(TASK_CLAIMS).set(TASK_CLAIMS.ATTEMPT, 3)
                .set(TASK_CLAIMS.EXPIRES_AT, OffsetDateTime.now().minusMinutes(1))
                .where(TASK_CLAIMS.CLAIM_TOKEN.eq(c.claimToken())).execute();

        reaper.reap();

        assertThat(tasks.findById(c.task().id()).orElseThrow().status())
                .isEqualTo(TaskStatus.FAILED);
    }

    @Test
    void active_claims_lists_only_worker_owned() {
        taskService.createGraph(List.of(
                new TaskDef("a", "A", null, "dev", null, null, null),
                new TaskDef("b", "B", null, "dev", null, null, null)), List.of());
        claimService.claimNext("dev", "worker-A");
        claimService.claimNext("dev", "worker-B");

        assertThat(claimService.activeClaims("worker-A")).hasSize(1);
        assertThat(claimService.activeClaims("worker-B")).hasSize(1);
        assertThat(claimService.activeClaims("nobody")).isEmpty();
    }

    @Test
    void dep_artifacts_returns_parent_metadata() {
        // root -> downstream; complete root, then check downstream's deps.
        Task root = taskService.createGraph(List.of(
                new TaskDef("root", "Root task", null, "dev", null, null, null),
                new TaskDef("child", "Child task", null, "dev", null, null, null)),
                List.of(new EdgeKey("root", "child"))).get(0);
        // Only root is ready, but we look up child below.
        ClaimedTask c = claimService.claimNext("dev", "worker-A").orElseThrow();
        claimService.complete(c.claimToken(), "root done",
                java.util.Map.of("api_url", "https://example.test"));

        Task child = tasks.findRecent(null, 10).stream()
                .filter(x -> x.title().equals("Child task")).findFirst().orElseThrow();
        var deps = claimService.dependencyArtifacts(child.id());
        assertThat(deps).containsKey(root.id());
        assertThat(deps.get(root.id())).containsEntry("summary", "root done");
        assertThat(deps.get(root.id()).get("artifacts").toString()).contains("api_url");
    }
}
