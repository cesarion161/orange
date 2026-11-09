package com.ai.orange.run;

import static com.ai.orange.run.RunTestSupport.git;
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
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** An implement run that reports done hands the task to verification, but only with commits to verify. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ImplementDoneIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("implement-done-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("implement-done-it-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
    }

    @Autowired WorkerRunner runner;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository taskEvents;
    @Autowired WorktreeService worktrees;
    @Autowired SchedulerPause pause;

    @BeforeEach
    void unpause() {
        pause.resume();
    }

    @Test
    void doneWithCommitsMovesToVerifying() {
        Task task = runningTask(taskService, tasks, "Print hello.");

        RunResult result = runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer", "{}"));

        assertThat(result.status()).as(String.valueOf(result.reason())).isEqualTo(RunStatus.SUCCEEDED);
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.VERIFYING);
        assertThat(after.branch()).isEqualTo("orange/" + task.id());
        assertThat(after.headSha()).isEqualTo(git(worktrees.resolve(task.id()), "rev-parse", "HEAD"));
        assertThat(after.failures()).isZero();
        assertThat(taskEventTypes(taskEvents, task.id())).contains("verification_started");
    }

    @Test
    void doneWithoutCommitsIsAFailure() {
        Task task = runningTask(taskService, tasks, "[[fake:no-commit]]");

        RunResult result = runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer", "{}"));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.reason()).isEqualTo("no_commits");
        assertThat(runs.findById(result.runId()).orElseThrow().error()).isEqualTo("no_commits");
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(after.failures()).isEqualTo(1);
        assertThat(after.headSha()).isNull();
    }
}
