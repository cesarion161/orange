package com.ai.orange.planner;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
 * Exercises the chat-as-planner code path: hand a {@link PlanJson} with mixed
 * per-task pipelines + overrides directly into {@link PlannerService#submitPlan},
 * and check that those values land on the {@code tasks.pipeline} column and
 * {@code tasks.metadata}. No subprocess runner required.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlannerSubmitPlanIT {

    static Path plannerOutputRoot;

    @Autowired PlannerService service;
    @Autowired TaskRepository taskRepo;
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
    void submit_plan_honors_per_task_pipeline_and_persists_overrides() throws Exception {
        PlanJson plan = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("a", "Quick refactor", null, "dev", 100,
                                "dev_only", null),
                        new PlanJson.PlanTask("b", "Behavior change", null, "dev", 100,
                                "dev_qa", Map.of("skip_review", true)),
                        new PlanJson.PlanTask("c", "Security-critical", null, "dev", 100,
                                "dev_review_qa", Map.of("requires_auth", true))),
                List.of(
                        new PlanJson.PlanEdge("a", "b"),
                        new PlanJson.PlanEdge("b", "c")));

        PlannerService.PlanResult result = service.submitPlan(plan, "# arch", null);

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.createdTasks()).hasSize(3);

        var byTitle = result.createdTasks().stream()
                .collect(java.util.stream.Collectors.toMap(Task::title, t -> t));
        assertThat(byTitle.get("Quick refactor").pipeline()).isEqualTo("dev_only");
        assertThat(byTitle.get("Behavior change").pipeline()).isEqualTo("dev_qa");
        assertThat(byTitle.get("Security-critical").pipeline()).isEqualTo("dev_review_qa");

        // Root-of-DAG is the only READY task.
        assertThat(byTitle.get("Quick refactor").status()).isEqualTo(TaskStatus.READY);
        assertThat(byTitle.get("Behavior change").status()).isEqualTo(TaskStatus.PENDING);
        assertThat(byTitle.get("Security-critical").status()).isEqualTo(TaskStatus.PENDING);

        // Overrides round-trip into metadata.
        Task b = taskRepo.findById(byTitle.get("Behavior change").id()).orElseThrow();
        assertThat(b.metadata().data()).contains("overrides").contains("skip_review").contains("true");
        Task c = taskRepo.findById(byTitle.get("Security-critical").id()).orElseThrow();
        assertThat(c.metadata().data()).contains("overrides").contains("requires_auth");

        // architecture.md is written alongside plan.json
        assertThat(result.architectureMd()).isNotNull();
        assertThat(Files.exists(result.architectureMd())).isTrue();
    }

    @Test
    void caller_default_applies_to_tasks_missing_an_explicit_pipeline() throws Exception {
        PlanJson plan = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("a", "Refactor", null, "dev", null, null, null),
                        new PlanJson.PlanTask("b", "Feature", null, "dev", null, "dev_qa", null)),
                List.of());

        PlannerService.PlanResult result = service.submitPlan(plan, null, "dev_review_qa");

        assertThat(result.status()).isEqualTo("success");
        var byTitle = result.createdTasks().stream()
                .collect(java.util.stream.Collectors.toMap(Task::title, t -> t));
        assertThat(byTitle.get("Refactor").pipeline()).isEqualTo("dev_review_qa");
        assertThat(byTitle.get("Feature").pipeline()).isEqualTo("dev_qa");
    }

    @Test
    void unknown_pipeline_on_a_task_rejects_the_whole_plan() {
        PlanJson plan = new PlanJson(
                List.of(new PlanJson.PlanTask("a", "x", null, "dev", null, "bogus_pipeline", null)),
                List.of());

        assertThatThrownBy(() -> service.submitPlan(plan, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus_pipeline");

        // No partial insert.
        assertThat(dsl.fetchCount(TASKS)).isZero();
    }

    @Test
    void unknown_caller_default_rejects_the_plan() {
        PlanJson plan = new PlanJson(
                List.of(new PlanJson.PlanTask("a", "x", null, "dev", null, null, null)),
                List.of());

        assertThatThrownBy(() -> service.submitPlan(plan, null, "no_such_pipeline"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no_such_pipeline");
    }
}
