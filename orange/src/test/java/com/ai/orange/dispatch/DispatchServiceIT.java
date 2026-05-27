package com.ai.orange.dispatch;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.planner.PlanJson;
import com.ai.orange.planner.PlannerService;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * Exercises the dispatcher: ingest a {@link PlanJson} (inline or by saved
 * {@code planId}) and check tasks + edges land in Postgres with the root marked
 * READY, per-task pipelines resolved, overrides persisted, and bad plans
 * rejected without partial inserts. {@code auto-start=false} so ingest can mark
 * roots READY without launching real workflows.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DispatchServiceIT {

    static Path plannerOutputRoot;

    @Autowired DispatchService dispatch;
    @Autowired PlannerService planner;
    @Autowired TaskRepository taskRepo;
    @Autowired DSLContext dsl;

    @BeforeAll
    static void prepare() throws Exception {
        plannerOutputRoot = Files.createTempDirectory("dispatch-it-");
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
    void dispatch_inline_creates_graph_with_root_ready_and_persists_pipelines_and_overrides() throws Exception {
        PlanJson plan = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("a", "Quick refactor", null, "dev", 100, "dev_only", null),
                        new PlanJson.PlanTask("b", "Behavior change", null, "dev", 100, "dev_qa",
                                Map.of("skip_review", true)),
                        new PlanJson.PlanTask("c", "Security-critical", null, "dev", 100, "dev_review_qa",
                                Map.of("requires_auth", true))),
                List.of(
                        new PlanJson.PlanEdge("a", "b"),
                        new PlanJson.PlanEdge("b", "c")));

        DispatchService.DispatchResult result = dispatch.dispatch(plan, "# arch", null);

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.planId()).isNotNull();
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

        assertThat(dsl.fetchCount(TASK_EDGES)).isEqualTo(2);

        // Overrides round-trip into metadata.
        Task b = taskRepo.findById(byTitle.get("Behavior change").id()).orElseThrow();
        assertThat(b.metadata().data()).contains("overrides").contains("skip_review").contains("true");
        Task c = taskRepo.findById(byTitle.get("Security-critical").id()).orElseThrow();
        assertThat(c.metadata().data()).contains("overrides").contains("requires_auth");
    }

    @Test
    void caller_default_applies_to_tasks_missing_an_explicit_pipeline() throws Exception {
        PlanJson plan = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("a", "Refactor", null, "dev", null, null, null),
                        new PlanJson.PlanTask("b", "Feature", null, "dev", null, "dev_qa", null)),
                List.of());

        DispatchService.DispatchResult result = dispatch.dispatch(plan, null, "dev_review_qa");

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

        assertThatThrownBy(() -> dispatch.dispatch(plan, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bogus_pipeline");

        assertThat(dsl.fetchCount(TASKS)).isZero();
    }

    @Test
    void unknown_caller_default_rejects_the_plan() {
        PlanJson plan = new PlanJson(
                List.of(new PlanJson.PlanTask("a", "x", null, "dev", null, null, null)),
                List.of());

        assertThatThrownBy(() -> dispatch.dispatch(plan, null, "no_such_pipeline"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no_such_pipeline");

        assertThat(dsl.fetchCount(TASKS)).isZero();
    }

    @Test
    void dispatch_by_plan_id_runs_a_plan_the_planner_saved() throws Exception {
        // Stage a plan on disk through the planner's save path...
        PlanJson plan = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("root", "Root task", null, "dev", null, "dev_only", null),
                        new PlanJson.PlanTask("leaf", "Leaf task", null, "dev", null, "dev_only", null)),
                List.of(new PlanJson.PlanEdge("root", "leaf")));
        PlannerService.PlanResult saved = planner.savePlan(plan, null);
        assertThat(dsl.fetchCount(TASKS)).isZero(); // save creates nothing

        // ...then dispatch it by id.
        DispatchService.DispatchResult result = dispatch.dispatchByPlanId(saved.planId(), null);

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.planId()).isEqualTo(saved.planId());
        assertThat(result.createdTasks()).hasSize(2);
        var byTitle = result.createdTasks().stream()
                .collect(java.util.stream.Collectors.toMap(Task::title, t -> t));
        assertThat(byTitle.get("Root task").status()).isEqualTo(TaskStatus.READY);
        assertThat(byTitle.get("Leaf task").status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void dispatch_by_unknown_plan_id_throws_plan_not_found() {
        assertThatThrownBy(() -> dispatch.dispatchByPlanId(UUID.randomUUID(), null))
                .isInstanceOf(PlanNotFoundException.class);
    }
}
