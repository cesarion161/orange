package com.ai.orange.planner;

import static com.ai.orange.db.jooq.Tables.AGENTS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.harness.FakeClaude;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drives {@link PlannerService} end-to-end with the fake Claude Code, whose
 * {@code [[fake:plan]]} scenario (selected through the planner agent's system
 * prompt) writes a canned 3-task DAG. Verifies the planner is <b>plan-only</b>:
 * it returns the DAG + artifacts for review and creates no tasks (dispatch is a
 * separate step — see {@code DispatchServiceIT}).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlannerServiceIT {

    static Path plannerOutputRoot;
    static Path runRoot;

    @Autowired PlannerService service;
    @Autowired DSLContext dsl;

    String originalPlannerPrompt;

    @BeforeAll
    static void prepare() throws IOException {
        Path tmp = Files.createTempDirectory("planner-it-");
        plannerOutputRoot = tmp.resolve("plans");
        runRoot = tmp.resolve("runs");
        Files.createDirectories(plannerOutputRoot);
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("orange.planner.output-root", () -> plannerOutputRoot.toString());
        FakeClaude.register(r, runRoot);
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
        originalPlannerPrompt = dsl.select(AGENTS.SYSTEM_PROMPT).from(AGENTS)
                .where(AGENTS.ROLE.eq("planner")).fetchOne(AGENTS.SYSTEM_PROMPT);
        dsl.update(AGENTS).set(AGENTS.SYSTEM_PROMPT, originalPlannerPrompt + "\n[[fake:plan]]")
                .where(AGENTS.ROLE.eq("planner")).execute();
    }

    @AfterEach
    void restorePlannerPrompt() {
        dsl.update(AGENTS).set(AGENTS.SYSTEM_PROMPT, originalPlannerPrompt).where(AGENTS.ROLE.eq("planner")).execute();
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

    @Test
    void planner_failure_is_reported_without_a_plan() throws Exception {
        dsl.update(AGENTS).set(AGENTS.SYSTEM_PROMPT, originalPlannerPrompt + "\n[[fake:error]]")
                .where(AGENTS.ROLE.eq("planner")).execute();
        PlannerService.PlanResult result = service.plan("This planner run fails.");
        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.plan()).isNull();
    }
}
