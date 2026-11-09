package com.ai.orange.dispatch;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.json.Json;
import com.ai.orange.planner.PlanJson;
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
 * READY, overrides persisted, and bad plans rejected without partial inserts.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DispatchServiceIT {

    static Path plannerOutputRoot;

    @Autowired DispatchService dispatch;
    @Autowired TaskRepository taskRepo;
    @Autowired DSLContext dsl;

    @BeforeAll
    static void prepare() throws Exception {
        plannerOutputRoot = Files.createTempDirectory("dispatch-it-");
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("orange.planner.output-root", () -> plannerOutputRoot.toString());
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void dispatch_inline_creates_graph_with_root_ready_and_persists_overrides() throws Exception {
        PlanJson plan = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("a", "Quick refactor", null, "dev", 100, null),
                        new PlanJson.PlanTask("b", "Behavior change", null, "dev", 100,
                                Map.of("skip_review", true)),
                        new PlanJson.PlanTask("c", "Security-critical", null, "dev", 100,
                                Map.of("requires_auth", true))),
                List.of(
                        new PlanJson.PlanEdge("a", "b"),
                        new PlanJson.PlanEdge("b", "c")));

        DispatchService.DispatchResult result = dispatch.dispatch(plan, "# arch");

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.planId()).isNotNull();
        assertThat(result.createdTasks()).hasSize(3);

        var byTitle = result.createdTasks().stream()
                .collect(java.util.stream.Collectors.toMap(Task::title, t -> t));

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
    void cyclic_plan_rejects_the_whole_plan() {
        PlanJson plan = new PlanJson(
                List.of(new PlanJson.PlanTask("a", "x", null, "dev", null, null),
                        new PlanJson.PlanTask("b", "y", null, "dev", null, null)),
                List.of(new PlanJson.PlanEdge("a", "b"), new PlanJson.PlanEdge("b", "a")));

        assertThatThrownBy(() -> dispatch.dispatch(plan, null)).isInstanceOf(RuntimeException.class);

        assertThat(dsl.fetchCount(TASKS)).isZero();
    }

    @Test
    void dispatch_by_plan_id_runs_a_plan_the_planner_saved() throws Exception {
        // A planner run leaves plan.json under <output-root>/<planId>/.
        PlanJson plan = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("root", "Root task", null, "dev", null, null),
                        new PlanJson.PlanTask("leaf", "Leaf task", null, "dev", null, null)),
                List.of(new PlanJson.PlanEdge("root", "leaf")));
        UUID planId = UUID.randomUUID();
        Path dir = Files.createDirectories(plannerOutputRoot.resolve(planId.toString()));
        Files.writeString(dir.resolve("plan.json"), Json.mapper().writeValueAsString(plan));

        DispatchService.DispatchResult result = dispatch.dispatchByPlanId(planId);

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.planId()).isEqualTo(planId);
        assertThat(result.createdTasks()).hasSize(2);
        var byTitle = result.createdTasks().stream()
                .collect(java.util.stream.Collectors.toMap(Task::title, t -> t));
        assertThat(byTitle.get("Root task").status()).isEqualTo(TaskStatus.READY);
        assertThat(byTitle.get("Leaf task").status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void dispatch_by_unknown_plan_id_throws_plan_not_found() {
        assertThatThrownBy(() -> dispatch.dispatchByPlanId(UUID.randomUUID()))
                .isInstanceOf(PlanNotFoundException.class);
    }
}
