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
 * End-to-end happy-path test for {@link PipelineActivitiesImpl}. Drives the
 * activity sequence (claim → prepareWorktree → runStage → markPrOpen →
 * cleanupWorktree) directly against a real Postgres + a real temp git repo +
 * a stub Python agent-runner. Bypasses Temporal in this test since the
 * workflow is just sequencing — its activities are where the logic lives.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, PipelineActivitiesIT.StubLauncherConfig.class})
@EnabledIf("isPython3AndGitAvailable")
class PipelineActivitiesIT {

    private static final String DEFAULT_AGENT_ROLE = "dev";

    static Path baseRepo;
    static Path worktreesRoot;
    static Path stubScript;

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

        // Copy the stub from the test classpath to a stable file.
        stubScript = Files.createTempFile("stub_runner_simple-", ".py");
        try (var in = PipelineActivitiesIT.class.getResourceAsStream("/stub_runner_simple.py")) {
            if (in == null) throw new IllegalStateException("stub_runner_simple.py not on classpath");
            Files.copy(in, stubScript, StandardCopyOption.REPLACE_EXISTING);
        }
        stubScript.toFile().deleteOnExit();
    }

    @DynamicPropertySource
    static void registerWorktreeProps(DynamicPropertyRegistry r) {
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

        String worktreePath = activities.prepareWorktree(taskId, "main");
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

    /**
     * Replaces the production AgentRunnerLauncher with one that runs the stub
     * Python script (which never touches Claude or the network).
     */
    @TestConfiguration
    static class StubLauncherConfig {
        @Bean
        @Primary
        AgentRunnerLauncher stubAgentRunnerLauncher() {
            AgentRunnerProperties props = new AgentRunnerProperties(
                    "python3", "ignored", Duration.ofSeconds(10), Duration.ofSeconds(5));
            return new AgentRunnerLauncher(props) {
                @Override
                public AgentRunnerProcess launch(Command.Start start, Consumer<Event> cb) throws IOException {
                    return AgentRunnerProcess.launch(
                            List.of("python3", "-u", stubScript.toString()),
                            Duration.ofSeconds(5),
                            start, cb);
                }
            };
        }
    }
}
