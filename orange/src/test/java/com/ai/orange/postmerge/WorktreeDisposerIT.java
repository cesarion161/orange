package com.ai.orange.postmerge;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeService;
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
 * End-to-end disposal: create a real worktree, terminate the task, run
 * the disposer, assert the worktree dir is gone and {@code metadata.worktree_removed_at}
 * is stamped so re-runs become no-ops.
 *
 * Skips when git isn't available (CI without git → can't make a worktree).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnabledIf("isGitAvailable")
class WorktreeDisposerIT {

    static Path baseRepo;
    static Path worktreesRoot;

    @Autowired WorktreeDisposer disposer;
    @Autowired WorktreeService worktrees;
    @Autowired TaskRepository tasks;
    @Autowired TaskService taskService;
    @Autowired DSLContext dsl;

    static boolean isGitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void setup() throws Exception {
        Path tmp = Files.createTempDirectory("worktree-disposer-it-");
        baseRepo = tmp.resolve("repo");
        worktreesRoot = tmp.resolve("worktrees");
        Files.createDirectories(baseRepo);
        Files.createDirectories(worktreesRoot);
        // Tiny git repo so `git worktree add` works.
        run(baseRepo, "git", "init", "-q", "-b", "main");
        run(baseRepo, "git", "config", "user.email", "it@example.com");
        run(baseRepo, "git", "config", "user.name", "IT");
        run(baseRepo, "git", "commit", "--allow-empty", "-q", "-m", "init");
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).inheritIO().start();
        if (p.waitFor() != 0) throw new RuntimeException("cmd failed: " + String.join(" ", cmd));
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("orange.workflow.auto-start", () -> "false");
        r.add("orange.worktrees.base-repo", () -> baseRepo.toString());
        r.add("orange.worktrees.root", () -> worktreesRoot.toString());
        // Long initial delay so the @Scheduled run doesn't race the test.
        r.add("orange.worktrees.disposer-initial-delay", () -> "1h");
        r.add("orange.worktrees.disposer-delay", () -> "1h");
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void terminal_task_with_live_worktree_is_disposed() throws Exception {
        Task task = taskService.createGraph(
                List.of(new TaskDef("t", "T", null, "dev", null, null, null)),
                List.of()).get(0);

        Path wt = worktrees.create(task.id(), "main");
        assertThat(Files.exists(wt)).isTrue();

        // Move the task to a terminal status without going through the workflow.
        tasks.transitionStatus(task.id(), TaskStatus.READY, TaskStatus.IN_PROGRESS);
        // FAILED is reachable from IN_PROGRESS per the FSM.
        tasks.transitionStatus(task.id(), TaskStatus.IN_PROGRESS, TaskStatus.FAILED);

        disposer.sweep();

        assertThat(Files.exists(wt)).isFalse();
        Task reloaded = tasks.findById(task.id()).orElseThrow();
        assertThat(reloaded.metadata().data()).contains("worktree_removed_at");

        // Idempotent: second sweep no-ops (no candidate left).
        disposer.sweep();
        Task again = tasks.findById(task.id()).orElseThrow();
        assertThat(again.metadata().data()).contains("worktree_removed_at");
    }

    @Test
    void non_terminal_task_is_left_alone() throws Exception {
        Task task = taskService.createGraph(
                List.of(new TaskDef("t", "T", null, "dev", null, null, null)),
                List.of()).get(0);
        Path wt = worktrees.create(task.id(), "main");

        // Stay in READY — disposer must not touch it.
        disposer.sweep();
        assertThat(Files.exists(wt)).isTrue();
    }
}
