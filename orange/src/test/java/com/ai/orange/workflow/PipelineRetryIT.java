package com.ai.orange.workflow;

import static com.ai.orange.db.jooq.Tables.AGENTS;
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
import org.junit.jupiter.api.AfterEach;
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
 * Drives the {@code dev_qa} pipeline manually, mirroring the workflow's
 * iteration logic, against a real Postgres + temp git repo + the fake Claude
 * Code. The dev stage runs the fake's default success scenario; the tester's
 * scenario comes from a marker in the tester agent's system prompt, switched
 * from FAIL to PASS after the first QA run.
 *
 * Verifies:
 *  - first dev stage succeeds, then tester FAILs → workflow retries dev
 *  - dev's second attempt sees the QA report as augmentation
 *  - tester's second invocation PASSes → task lands at {@code pr_open}
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@EnabledIf("isGitAvailable")
class PipelineRetryIT {

    static Path baseRepo;
    static Path worktreesRoot;
    static Path runRoot;

    @Autowired PipelineActivitiesImpl activities;
    @Autowired TaskService taskService;
    @Autowired DSLContext dsl;
    @Autowired PipelineRegistry pipelines;

    String originalTesterPrompt;

    @BeforeAll
    static void prepare() throws Exception {
        Path tmp = Files.createTempDirectory("pipeline-retry-it-");
        baseRepo = tmp.resolve("base");
        worktreesRoot = tmp.resolve("worktrees");
        runRoot = tmp.resolve("runs");
        Files.createDirectories(baseRepo);
        Files.createDirectories(worktreesRoot);

        run(baseRepo, "git", "init", "-q", "-b", "main");
        run(baseRepo, "git", "config", "user.email", "test@example.com");
        run(baseRepo, "git", "config", "user.name", "Test");
        Files.writeString(baseRepo.resolve("README.md"), "phase 4 retry\n");
        run(baseRepo, "git", "add", ".");
        run(baseRepo, "git", "commit", "-q", "-m", "initial");
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
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
        originalTesterPrompt = dsl.select(AGENTS.SYSTEM_PROMPT).from(AGENTS)
                .where(AGENTS.ROLE.eq("tester")).fetchOne(AGENTS.SYSTEM_PROMPT);
    }

    @AfterEach
    void restoreTesterPrompt() {
        dsl.update(AGENTS).set(AGENTS.SYSTEM_PROMPT, originalTesterPrompt).where(AGENTS.ROLE.eq("tester")).execute();
    }

    void setTesterScenario(String marker) {
        dsl.update(AGENTS).set(AGENTS.SYSTEM_PROMPT, originalTesterPrompt + "\n" + marker)
                .where(AGENTS.ROLE.eq("tester")).execute();
    }

    @Test
    void dev_qa_pipeline_retries_dev_when_tester_fails_then_passes_on_retry() throws Exception {
        Pipeline pipeline = pipelines.getOrThrow("dev_qa");
        assertThat(pipeline.stages()).hasSize(2);

        TaskDef def = new TaskDef("retry-1", "Build feature with QA",
                "Should retry dev once after first tester run fails.",
                "dev", "dev_qa", null, null);
        UUID taskId = taskService.createGraph(List.of(def), List.<EdgeKey>of()).get(0).id();

        String workerId = "test-worker";
        assertThat(activities.claim(taskId, workerId)).isTrue();
        String worktreePath = activities.prepareWorktree(taskId);

        setTesterScenario("[[fake:tester-fail]]");

        // Mirror PipelineWorkflowImpl.iterateStages by hand so we can verify
        // the intermediate states without running Temporal.
        String augmentation = null;
        int idx = 0;
        int safety = 0;
        StageOutcome lastOutcome = null;

        while (idx < pipeline.stages().size() && safety++ < 8) {
            Stage stage = pipeline.stages().get(idx);
            StageOutcome outcome = activities.runStage(taskId, stage.role(), worktreePath, augmentation, null);
            lastOutcome = outcome;
            if ("tester".equals(stage.role()) && !outcome.succeeded()) {
                setTesterScenario("[[fake:tester-pass]]");
            }
            if (outcome.succeeded()) {
                augmentation = null;
                idx++;
            } else if (stage.onFailure() == OnFailure.RETRY_PREVIOUS && idx > 0) {
                augmentation = outcome.report();
                idx--;
            } else {
                break;
            }
        }

        // Final stage in the loop (qa) must have produced a PASS verdict.
        assertThat(lastOutcome).isNotNull();
        assertThat(lastOutcome.verdict()).isEqualTo("pass");
        assertThat(idx).isEqualTo(pipeline.stages().size());

        activities.markPrOpen(taskId);
        activities.cleanupWorktree(taskId);

        Task finalTask = taskService.findById(taskId).orElseThrow();
        assertThat(finalTask.status()).isEqualTo(TaskStatus.PR_OPEN);

        // We expect: 1st dev run + 1st tester (fail) + 2nd dev run + 2nd tester (pass) = 4 attempts.
        List<String> prompts = dsl.select(AGENT_RUNS.PROMPT).from(AGENT_RUNS)
                .where(AGENT_RUNS.TASK_ID.eq(taskId)).orderBy(AGENT_RUNS.ATTEMPT).fetch(AGENT_RUNS.PROMPT);
        assertThat(prompts).hasSize(4);
        // The second dev run (attempt 3) carried the failing QA report.
        assertThat(prompts.get(2)).contains("VERDICT: FAIL").contains("SnakeTest.moves failed");
        assertThat(prompts.get(0)).doesNotContain("VERDICT");
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        if (p.waitFor() != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out);
        }
    }
}
