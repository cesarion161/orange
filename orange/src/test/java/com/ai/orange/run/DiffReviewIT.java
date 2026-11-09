package com.ai.orange.run;

import static com.ai.orange.run.RunTestSupport.headInput;
import static com.ai.orange.run.RunTestSupport.start;
import static com.ai.orange.run.RunTestSupport.taskEventTypes;
import static com.ai.orange.run.RunTestSupport.verifyingTask;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.evidence.Evidence;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The LLM half of verification: a read-only reviewer judges the diff against the task's intent. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DiffReviewIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("diff-review-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("diff-review-it-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
    }

    @Autowired WorkerRunner runner;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired EvidenceRepository evidence;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository taskEvents;
    @Autowired WorktreeService worktrees;
    @Autowired SchedulerPause pause;

    @BeforeEach
    void unpause() {
        pause.resume();
    }

    RunResult review(Task task) {
        return runner.execute(start(runs, props, task.id(), RunKind.DIFF_REVIEW, "reviewer", headInput(task.headSha())));
    }

    @Test
    void passVerifiesTheTask() throws Exception {
        Task task = verifyingTask(taskService, tasks, worktrees, "Print hello.", "orange-fake.txt", "hello from the task\n");

        RunResult result = review(task);

        assertThat(result.status()).as(String.valueOf(result.reason())).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(evidence.findByTaskAndHead(task.id(), task.headSha())).singleElement().satisfies(e -> {
            assertThat(e.kind()).isEqualTo("diff_review");
            assertThat(e.passed()).isTrue();
            assertThat(e.runId()).isEqualTo(result.runId());
        });
        assertThat(evidence.hasPassingDiffReview(task.id(), task.headSha())).isTrue();
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.VERIFIED);
        assertThat(taskEventTypes(taskEvents, task.id())).contains("verified");

        String prompt = prompt(result);
        assertThat(prompt).contains("# Add a greeting").contains("Print hello.")
                .contains(task.headSha()).contains("orange-fake.txt").contains("+hello from the task");
        Run row = runs.findById(result.runId()).orElseThrow();
        assertThat(row.tier()).as("reviews route medium/low").isEqualTo("medium");
        assertThat(row.effort()).isEqualTo("low");
    }

    @Test
    void gapsSendTheTaskBack() {
        Task task = verifyingTask(taskService, tasks, worktrees, "[[fake:review-gaps]]", "orange-fake.txt", "hi\n");

        RunResult result = review(task);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(after.failures()).isEqualTo(1);
        Evidence row = evidence.findByTaskAndHead(task.id(), task.headSha()).getFirst();
        assertThat(row.passed()).isFalse();
        assertThat(row.outputTail()).contains("README.md:1").contains("The heading has a typo.")
                .contains("No test covers the change.");
        assertThat(taskEventTypes(taskEvents, task.id())).contains("review_gaps");
    }

    @Test
    void noOutcomeCounts() {
        Task task = verifyingTask(taskService, tasks, worktrees, "[[fake:no-outcome]]", "orange-fake.txt", "hi\n");

        RunResult result = review(task);

        assertThat(result.reason()).isEqualTo("no_outcome");
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).as("a failed review leaves the task waiting for another").isEqualTo(TaskStatus.VERIFYING);
        assertThat(after.failures()).isEqualTo(1);
        assertThat(evidence.findByTask(task.id())).isEmpty();
    }

    @Test
    void hugeDiffIsCapped() throws Exception {
        String big = "0123456789abcdef".repeat(65_536) + "\n"; // 1 MiB on one line
        Task task = verifyingTask(taskService, tasks, worktrees, "Add a big file.", "big.txt", big);

        RunResult result = review(task);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        String prompt = prompt(result);
        assertThat(prompt.length()).isLessThanOrEqualTo(RunPreparer.MAX_REVIEW_PROMPT + 1_000);
        assertThat(prompt).contains("cut").contains("big.txt");
    }

    private String prompt(RunResult result) throws IOException {
        return Files.readString(RUN_ROOT.resolve(result.runId() + "/claude-config/fake-prompt.txt"));
    }
}
