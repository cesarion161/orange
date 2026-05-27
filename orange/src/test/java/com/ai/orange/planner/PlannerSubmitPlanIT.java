package com.ai.orange.planner;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Exercises the chat-as-planner save path ({@link PlannerService#savePlan}):
 * hand a {@link PlanJson} directly and check the artifact is written and the
 * DAG validated, while <b>no tasks are created</b> — running the plan is the
 * dispatcher's job (covered by {@code DispatchServiceIT}). No subprocess
 * runner required.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlannerSubmitPlanIT {

    static Path plannerOutputRoot;

    @Autowired PlannerService service;
    @Autowired DSLContext dsl;

    @BeforeAll
    static void prepare() throws Exception {
        plannerOutputRoot = Files.createTempDirectory("planner-submit-it-");
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("orange.planner.output-root", () -> plannerOutputRoot.toString());
        r.add("orange.workflow.auto-start", () -> "false");
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void save_plan_writes_artifacts_and_returns_dag_without_creating_tasks() throws Exception {
        PlanJson plan = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("a", "Quick refactor", null, "dev", 100, "dev_only", null),
                        new PlanJson.PlanTask("b", "Behavior change", null, "dev", 100, "dev_qa", null),
                        new PlanJson.PlanTask("c", "Security-critical", null, "dev", 100, "dev_review_qa", null)),
                List.of(
                        new PlanJson.PlanEdge("a", "b"),
                        new PlanJson.PlanEdge("b", "c")));

        PlannerService.PlanResult result = service.savePlan(plan, "# arch");

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.plan()).isNotNull();
        assertThat(result.plan().tasks()).hasSize(3);
        assertThat(result.plan().edges()).hasSize(2);

        // plan.json + architecture.md written under the per-plan dir.
        assertThat(result.planJson()).isNotNull();
        assertThat(Files.exists(result.planJson())).isTrue();
        assertThat(result.architectureMd()).isNotNull();
        assertThat(Files.exists(result.architectureMd())).isTrue();

        // Plan-only: nothing inserted.
        assertThat(dsl.fetchCount(TASKS)).isZero();
        assertThat(dsl.fetchCount(TASK_EDGES)).isZero();
    }

    @Test
    void save_plan_rejects_a_cyclic_plan_and_creates_nothing() {
        PlanJson cyclic = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("a", "A", null, "dev", null, null, null),
                        new PlanJson.PlanTask("b", "B", null, "dev", null, null, null)),
                List.of(
                        new PlanJson.PlanEdge("a", "b"),
                        new PlanJson.PlanEdge("b", "a")));

        assertThatThrownBy(() -> service.savePlan(cyclic, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cycle");

        assertThat(dsl.fetchCount(TASKS)).isZero();
    }

    @Test
    void save_plan_rejects_a_duplicate_task_key() {
        PlanJson dup = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("a", "A", null, "dev", null, null, null),
                        new PlanJson.PlanTask("a", "A2", null, "dev", null, null, null)),
                List.of());

        assertThatThrownBy(() -> service.savePlan(dup, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate task key");
    }
}
