package com.ai.orange.workflow;

import static com.ai.orange.db.jooq.Tables.AGENT_RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.agent.AgentRunnerLauncher;
import com.ai.orange.agent.AgentRunnerProcess;
import com.ai.orange.agent.AgentRunnerProperties;
import com.ai.orange.agent.protocol.Command;
import com.ai.orange.agent.protocol.Event;
import com.ai.orange.task.EdgeKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drives the {@code dev_qa} pipeline manually, mirroring the workflow's
 * iteration logic, against a real Postgres + temp git repo + role-aware stub
 * launcher (dev → simple stub, tester → counter stub: FAIL then PASS).
 *
 * Verifies:
 *  - first dev stage succeeds, then tester FAILs → workflow retries dev
 *  - dev's second attempt sees the QA report as augmentation
 *  - tester's second invocation PASSes → task lands at {@code pr_open}
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, PipelineRetryIT.RoleAwareLauncherConfig.class})
@EnabledIf("isPython3AndGitAvailable")
class PipelineRetryIT {

    static Path baseRepo;
    static Path worktreesRoot;
    static Path devStub;
    static Path testerStub;

    @Autowired PipelineActivitiesImpl activities;
    @Autowired TaskService taskService;
    @Autowired DSLContext dsl;
    @Autowired PipelineRegistry pipelines;

    @BeforeAll
    static void prepare() throws Exception {
        Path tmp = Files.createTempDirectory("pipeline-retry-it-");
        baseRepo = tmp.resolve("base");
        worktreesRoot = tmp.resolve("worktrees");
        Files.createDirectories(baseRepo);
        Files.createDirectories(worktreesRoot);

        run(baseRepo, "git", "init", "-q", "-b", "main");
        run(baseRepo, "git", "config", "user.email", "test@example.com");
        run(baseRepo, "git", "config", "user.name", "Test");
        Files.writeString(baseRepo.resolve("README.md"), "phase 4 retry\n");
        run(baseRepo, "git", "add", ".");
        run(baseRepo, "git", "commit", "-q", "-m", "initial");

        devStub = copyResource("/stub_runner_simple.py", "dev-stub-");
        testerStub = copyResource("/stub_tester.py", "tester-stub-");
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("orange.worktrees.root", () -> worktreesRoot.toString());
        r.add("orange.worktrees.base-repo", () -> baseRepo.toString());
        r.add("orange.worktrees.max-count", () -> "20");
        r.add("orange.workflow.auto-start", () -> "false");
    }

    static boolean isPython3AndGitAvailable() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor() == 0
                    && new ProcessBuilder("git", "--version").start().waitFor() == 0;
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
    void dev_qa_pipeline_retries_dev_when_tester_fails_then_passes_on_retry() throws Exception {
        Pipeline pipeline = pipelines.getOrThrow("dev_qa");
        assertThat(pipeline.stages()).hasSize(2);

        TaskDef def = new TaskDef("retry-1", "Build feature with QA",
                "Should retry dev once after first tester run fails.",
                "dev", "dev_qa", null, null);
        UUID taskId = taskService.createGraph(List.of(def), List.<EdgeKey>of()).get(0).id();

        String workerId = "test-worker";
        assertThat(activities.claim(taskId, workerId)).isTrue();
        String worktreePath = activities.prepareWorktree(taskId, "main");

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
        int agentRunCount = dsl.fetchCount(AGENT_RUNS, AGENT_RUNS.TASK_ID.eq(taskId));
        assertThat(agentRunCount).isEqualTo(4);
    }

    private static Path copyResource(String classpathResource, String prefix) throws IOException {
        Path tmp = Files.createTempFile(prefix, ".py");
        try (var in = PipelineRetryIT.class.getResourceAsStream(classpathResource)) {
            if (in == null) throw new IllegalStateException(classpathResource + " not on classpath");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
        }
        tmp.toFile().deleteOnExit();
        return tmp;
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        if (p.waitFor() != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out);
        }
    }

    /**
     * Replaces the production launcher with one that picks the stub script
     * by looking at {@code start.options.systemPrompt} — our seeded tester's
     * prompt instructs writing {@code report.md} with a {@code VERDICT}
     * header, so we key off "VERDICT" appearing in the prompt.
     */
    @TestConfiguration
    static class RoleAwareLauncherConfig {
        @Bean
        @Primary
        AgentRunnerLauncher roleAwareLauncher() {
            AgentRunnerProperties props = new AgentRunnerProperties(
                    "python3", "ignored", Duration.ofSeconds(10), Duration.ofSeconds(5));
            return new AgentRunnerLauncher(props) {
                @Override
                public AgentRunnerProcess launch(Command.Start start, Consumer<Event> cb) throws IOException {
                    String prompt = start.options() == null ? "" : start.options().systemPrompt();
                    Path script = (prompt != null && prompt.contains("VERDICT")) ? testerStub : devStub;
                    return AgentRunnerProcess.launch(
                            List.of("python3", "-u", script.toString()),
                            Duration.ofSeconds(5), start, cb);
                }
            };
        }
    }
}
