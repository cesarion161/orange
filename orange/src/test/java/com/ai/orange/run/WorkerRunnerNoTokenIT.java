package com.ai.orange.run;

import static com.ai.orange.run.RunTestSupport.runningTask;
import static com.ai.orange.run.RunTestSupport.start;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A subscription tier with no plan token fails at once, without starting Claude Code, and pauses the scheduler. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class WorkerRunnerNoTokenIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("worker-run-notoken-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("worker-run-notoken-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
        r.add("orange.billing.subscription-token", () -> "");
    }

    @Autowired WorkerRunner runner;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired SchedulerPause pause;

    @Test
    void missingTokenFailsFastAndPauses() throws Exception {
        pause.resume();
        Task task = runningTask(taskService, tasks, "Print hello.");

        RunResult result = runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer", "{}"));

        assertThat(result.status()).isEqualTo(RunStatus.FAILED);
        assertThat(result.reason()).isEqualTo("subscription_token_missing");
        Run row = runs.findById(result.runId()).orElseThrow();
        assertThat(row.status()).isEqualTo(RunStatus.FAILED);
        assertThat(row.error()).isEqualTo("subscription_token_missing");
        assertThat(pause.current()).hasValueSatisfying(p -> {
            assertThat(p.reason()).isEqualTo("subscription_token_missing");
            assertThat(p.until()).as("open-ended until someone adds the token").isNull();
        });
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isZero();
        try (Stream<Path> entries = Files.list(RUN_ROOT)) {
            assertThat(entries).as("Claude Code must not have been started").isEmpty();
        }
        pause.resume();
    }
}
