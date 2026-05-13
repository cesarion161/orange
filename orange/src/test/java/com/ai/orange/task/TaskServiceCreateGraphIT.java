package com.ai.orange.task;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.exception.DagCycleException;
import java.util.List;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TaskServiceCreateGraphIT {

    @Autowired private TaskService taskService;
    @Autowired private TaskRepository taskRepository;
    @Autowired private DSLContext dsl;

    @BeforeEach
    void cleanTables() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void linear_graph_marks_only_root_ready() {
        var defs = List.of(
                new TaskDef("a", "Build A", null, "dev", null, null, null),
                new TaskDef("b", "Build B", null, "dev", null, null, null),
                new TaskDef("c", "Build C", null, "dev", null, null, null));
        var edges = List.of(new EdgeKey("a", "b"), new EdgeKey("b", "c"));

        List<Task> created = taskService.createGraph(defs, edges);

        assertThat(created).hasSize(3);
        var byTitle = created.stream().collect(java.util.stream.Collectors.toMap(Task::title, t -> t));
        assertThat(byTitle.get("Build A").status()).isEqualTo(TaskStatus.READY);
        assertThat(byTitle.get("Build B").status()).isEqualTo(TaskStatus.PENDING);
        assertThat(byTitle.get("Build C").status()).isEqualTo(TaskStatus.PENDING);
    }

    @Test
    void parallel_roots_both_ready() {
        var defs = List.of(
                new TaskDef("a", "A", null, "dev", null, null, null),
                new TaskDef("b", "B", null, "dev", null, null, null),
                new TaskDef("merge", "Merge", null, "dev", null, null, null));
        var edges = List.of(new EdgeKey("a", "merge"), new EdgeKey("b", "merge"));

        List<Task> created = taskService.createGraph(defs, edges);

        assertThat(created).filteredOn(t -> t.status() == TaskStatus.READY)
                .extracting(Task::title)
                .containsExactlyInAnyOrder("A", "B");
        assertThat(created).filteredOn(t -> t.status() == TaskStatus.PENDING)
                .extracting(Task::title)
                .containsExactly("Merge");
    }

    @Test
    void rejects_cyclic_graph_before_inserting_anything() {
        var defs = List.of(
                new TaskDef("a", "A", null, "dev", null, null, null),
                new TaskDef("b", "B", null, "dev", null, null, null),
                new TaskDef("c", "C", null, "dev", null, null, null));
        var edges = List.of(
                new EdgeKey("a", "b"),
                new EdgeKey("b", "c"),
                new EdgeKey("c", "a"));

        assertThatThrownBy(() -> taskService.createGraph(defs, edges))
                .isInstanceOf(DagCycleException.class)
                .hasMessageContaining("a");

        // Critical: nothing should have been inserted because validation runs
        // before any DB writes.
        assertThat(dsl.fetchCount(TASKS)).isZero();
        assertThat(dsl.fetchCount(TASK_EDGES)).isZero();
    }

    @Test
    void rejects_duplicate_task_keys() {
        var defs = List.of(
                new TaskDef("a", "A1", null, "dev", null, null, null),
                new TaskDef("a", "A2", null, "dev", null, null, null));

        assertThatThrownBy(() -> taskService.createGraph(defs, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate task key");
    }

    @Test
    void rejects_edge_referencing_unknown_node() {
        var defs = List.of(new TaskDef("a", "A", null, "dev", null, null, null));
        var edges = List.of(new EdgeKey("a", "ghost"));

        assertThatThrownBy(() -> taskService.createGraph(defs, edges))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }
}
