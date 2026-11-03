package com.ai.orange.workflow;

import static com.ai.orange.db.jooq.Tables.AGENT_RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.harness.FakeClaude;
import com.ai.orange.task.EdgeKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * End-to-end happy-path test for {@link PipelineActivitiesImpl}. Drives the
 * activity sequence (claim → prepareWorktree → runStage → markPrOpen →
 * cleanupWorktree) directly against a real Postgres + a real temp git repo +
 * the fake Claude Code (src/test/resources/fake-claude). Bypasses Temporal in
 * this test since the workflow is just sequencing — its activities are where
 * the logic lives.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnabledIf("isGitAvailable")
class PipelineActivitiesIT {

    private static final String DEFAULT_AGENT_ROLE = "dev";

    static Path baseRepo;
    static Path worktreesRoot;
    static Path runRoot;

    @Autowired PipelineActivitiesImpl activities;
    @Autowired TaskService taskService;
    @Autowired DSLContext dsl;

    @BeforeAll
    static void prepareFilesystemAndPaths() throws Exception {
        Path tmp = Files.createTempDirectory("pipeline-it-");
        baseRepo = tmp.resolve("base");
        worktreesRoot = tmp.resolve("worktrees");
        Files.createDirectories(baseRepo);
        Files.createDirectories(worktreesRoot);

        // Init a tiny git repo with one commit on main.
        run(baseRepo, "git", "init", "-q", "-b", "main");
        run(baseRepo, "git", "config", "user.email", "test@example.com");
        run(baseRepo, "git", "config", "user.name", "Test");
        Files.writeString(baseRepo.resolve("README.md"), "phase 4\n");
        run(baseRepo, "git", "add", ".");
        run(baseRepo, "git", "commit", "-q", "-m", "initial");
        runRoot = tmp.resolve("runs");
    }

    @DynamicPropertySource
    static void registerWorktreeProps(DynamicPropertyRegistry r) {
        r.add("orange.worktrees.root", () -> worktreesRoot.toString());
        r.add("orange.worktrees.base-repo", () -> baseRepo.toString());
        r.add("orange.worktrees.max-count", () -> "20");
        FakeClaude.register(r, runRoot);
    }

    static boolean isGitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void cleanTables() {
        dsl.deleteFrom(AGENT_RUNS).execute();
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void happy_path_drives_task_through_to_pr_open() throws Exception {
        TaskDef def = new TaskDef("dev-only-1", "Demo task",
                "Make the agent emit a final.success event.",
                DEFAULT_AGENT_ROLE, "dev_only", null, null);

        List<Task> created = taskService.createGraph(List.of(def), List.<EdgeKey>of());
        UUID taskId = created.get(0).id();
        assertThat(created.get(0).status()).isEqualTo(TaskStatus.READY);

        String workerId = "test-worker";
        boolean claimed = activities.claim(taskId, workerId);
        assertThat(claimed).as("claim should take an unclaimed READY task").isTrue();

        // Idempotent re-claim by same worker should also return true.
        assertThat(activities.claim(taskId, workerId)).isTrue();

        String worktreePath = activities.prepareWorktree(taskId);
        assertThat(Path.of(worktreePath)).exists().isDirectory();

        StageOutcome outcome = activities.runStage(taskId, "dev", worktreePath, null, null);
        assertThat(outcome.agentStatus()).isEqualTo("success");
        assertThat(outcome.succeeded()).isTrue();

        activities.markPrOpen(taskId);
        activities.cleanupWorktree(taskId);

        Task finalTask = taskService.findById(taskId).orElseThrow();
        assertThat(finalTask.status()).isEqualTo(TaskStatus.PR_OPEN);
        assertThat(Path.of(worktreePath)).doesNotExist();

        // Events were persisted.
        int eventCount = dsl.fetchCount(TASK_EVENTS, TASK_EVENTS.TASK_ID.eq(taskId));
        assertThat(eventCount)
                .as("ready + assistant_message + final → 3 task_events rows")
                .isGreaterThanOrEqualTo(3);

        // The agent_run row for this attempt is finalised.
        int agentRunCount = dsl.fetchCount(AGENT_RUNS, AGENT_RUNS.TASK_ID.eq(taskId));
        assertThat(agentRunCount).isEqualTo(1);
    }

    @Test
    void reviewer_runStage_captures_final_message_as_the_review() throws Exception {
        // runStage with role "reviewer" must capture the agent's final message
        // as the review (so a VERDICT line can gate). The fake emits no VERDICT,
        // so the review is advisory (verdict null → succeeded).
        TaskDef def = new TaskDef("rev-1", "Review me", null, DEFAULT_AGENT_ROLE, "dev_review_qa", null, null);
        UUID taskId = taskService.createGraph(List.of(def), List.<EdgeKey>of()).get(0).id();
        activities.claim(taskId, "rev-worker");
        String worktreePath = activities.prepareWorktree(taskId);

        StageOutcome outcome = activities.runStage(taskId, "reviewer", worktreePath, null, null);

        assertThat(outcome.agentStatus()).isEqualTo("success");
        // The fake's last assistant message was captured as the review report.
        assertThat(outcome.report()).isEqualTo("Done.");
        // No VERDICT line in the fake's output → advisory, non-blocking.
        assertThat(outcome.verdict()).isNull();
        assertThat(outcome.succeeded()).isTrue();

        activities.cleanupWorktree(taskId);
    }

    @Test
    void second_worker_cannot_claim_a_task_already_owned() throws Exception {
        TaskDef def = new TaskDef("race-1", "Race", null, DEFAULT_AGENT_ROLE, "dev_only", null, null);
        UUID taskId = taskService.createGraph(List.of(def), List.of()).get(0).id();

        assertThat(activities.claim(taskId, "worker-A")).isTrue();
        assertThat(activities.claim(taskId, "worker-B")).isFalse();
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        if (p.waitFor() != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out);
        }
    }
}
