package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.run.RunTestSupport.runningTask;
import static com.ai.orange.run.RunTestSupport.start;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.worktree.WorktreeService;
import java.nio.file.Path;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A full worktree root is a reason to wait, not a broken workspace: nothing pauses, nothing counts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WorktreeCapIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("worktree-cap-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("worktree-cap-it-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
        r.add("orange.worktrees.max-count", () -> "1");
    }

    @Autowired WorkerRunner runner;
    @Autowired TaskReactor reactor;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired WorktreeService worktrees;
    @Autowired SchedulerPause pause;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        pause.resume();
        dsl.deleteFrom(RUNS).execute();
    }

    @Test
    void aFullWorktreeRootIsNotAPause() throws Exception {
        Task holder = runningTask(taskService, tasks, "x");
        worktrees.ensure(holder.id()); // the one slot is taken
        Task waiting = runningTask(taskService, tasks, "Print hello.");

        RunResult result = runner.execute(start(runs, props, waiting.id(), RunKind.IMPLEMENT, "implementer", "{}"));

        assertThat(result.reason()).isEqualTo("no_worktree_slot");
        assertThat(pause.current()).as("one task waiting for a slot does not stop the others").isEmpty();
        assertThat(tasks.findById(waiting.id()).orElseThrow().failures()).isZero();

        reactor.react(waiting.id());
        assertThat(runs.findActiveByTask(waiting.id())).as("it waits for a slot instead of retrying at once").isEmpty();
    }
}
