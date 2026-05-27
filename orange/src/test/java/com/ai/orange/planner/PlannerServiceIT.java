package com.ai.orange.planner;

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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
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
 * Drives {@link PlannerService} end-to-end using a stub planner agent that
 * writes a canned 3-task DAG. Verifies the planner is <b>plan-only</b>: it
 * returns the DAG + artifacts for review and creates no tasks (dispatch is a
 * separate step — see {@code DispatchServiceIT}).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, PlannerServiceIT.StubPlannerLauncherConfig.class})
@EnabledIf("isPython3Available")
class PlannerServiceIT {

    static Path stubScript;
    static Path plannerOutputRoot;

    @Autowired PlannerService service;
    @Autowired DSLContext dsl;

    @BeforeAll
    static void prepare() throws IOException {
        Path tmp = Files.createTempDirectory("planner-it-");
        plannerOutputRoot = tmp.resolve("plans");
        Files.createDirectories(plannerOutputRoot);
        stubScript = Files.createTempFile("stub_planner-", ".py");
        try (var in = PlannerServiceIT.class.getResourceAsStream("/stub_planner.py")) {
            if (in == null) throw new IllegalStateException("stub_planner.py not on classpath");
            Files.copy(in, stubScript, StandardCopyOption.REPLACE_EXISTING);
        }
        stubScript.toFile().deleteOnExit();
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("orange.planner.output-root", () -> plannerOutputRoot.toString());
        r.add("orange.workflow.auto-start", () -> "false");
    }

    static boolean isPython3Available() {
        try {
            return new ProcessBuilder("python3", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void plan_returns_dag_for_review_and_creates_no_tasks() throws Exception {
        PlannerService.PlanResult result = service.plan("Build a TODO web app with auth.");

        assertThat(result.status()).isEqualTo("success");

        // The reviewable DAG comes back in the result — 3 tasks, 2 edges.
        assertThat(result.plan()).isNotNull();
        assertThat(result.plan().tasks()).hasSize(3);
        assertThat(result.plan().edges()).hasSize(2);

        var byTitle = result.plan().tasks().stream()
                .collect(java.util.stream.Collectors.toMap(PlanJson.PlanTask::title, t -> t));
        assertThat(byTitle).containsKeys("Build auth module", "Build UI", "Write integration tests");
        assertThat(byTitle.get("Write integration tests").role()).isEqualTo("tester");

        // Architecture + plan files are reachable on disk.
        assertThat(result.architectureMd()).isNotNull();
        assertThat(Files.exists(result.architectureMd())).isTrue();
        assertThat(result.planJson()).isNotNull();
        assertThat(Files.exists(result.planJson())).isTrue();

        // Plan-only: nothing was inserted — that's the dispatcher's job.
        assertThat(dsl.fetchCount(TASKS)).isZero();
        assertThat(dsl.fetchCount(TASK_EDGES)).isZero();
    }

    @Test
    void planner_output_dir_is_per_plan_uuid_and_persists_after_call() throws Exception {
        PlannerService.PlanResult r1 = service.plan("First request");
        PlannerService.PlanResult r2 = service.plan("Second request");

        assertThat(r1.planId()).isNotEqualTo(r2.planId());
        assertThat(r1.outputDir()).isNotEqualTo(r2.outputDir());
        assertThat(Files.exists(r1.outputDir())).isTrue();
        assertThat(Files.exists(r2.outputDir())).isTrue();
    }

    /**
     * Replaces the production launcher with one that runs the stub planner
     * Python script — the only way we can drive PlannerService in CI without
     * a real Anthropic key.
     */
    @TestConfiguration
    static class StubPlannerLauncherConfig {
        @Bean
        @Primary
        AgentRunnerLauncher stubPlannerLauncher() {
            AgentRunnerProperties props = new AgentRunnerProperties(
                    "python3", "ignored", Duration.ofSeconds(10), Duration.ofSeconds(5));
            return new AgentRunnerLauncher(props) {
                @Override
                public AgentRunnerProcess launch(Command.Start start, Consumer<Event> cb) throws IOException {
                    return AgentRunnerProcess.launch(
                            List.of("python3", "-u", stubScript.toString()),
                            Duration.ofSeconds(5), start, cb);
                }
            };
        }
    }

}
