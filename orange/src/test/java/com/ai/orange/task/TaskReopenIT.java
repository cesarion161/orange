package com.ai.orange.task;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Triage-side helpers on {@link TaskService}: reopen a FAILED/TEST_DONE task
 * back to READY (FSM extension), refuse to reopen CANCELLED (stays terminal),
 * and create a triage workload that lands on the ready queue.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TaskReopenIT {

    @Autowired TaskService service;
    @Autowired TaskRepository repo;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void failed_task_can_be_reopened_to_ready_with_reason_stamped() {
        UUID id = service.createGraph(
                List.of(new TaskDef("a", "A", null, "dev", null, null, null)),
                List.of()).get(0).id();
        repo.transitionStatus(id, TaskStatus.READY, TaskStatus.IN_PROGRESS);
        repo.transitionStatus(id, TaskStatus.IN_PROGRESS, TaskStatus.FAILED);

        boolean ok = service.reopenTask(id, "regression on main after merge", "triage-bot");
        assertThat(ok).isTrue();

        Task reloaded = repo.findById(id).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(TaskStatus.READY);
        assertThat(reloaded.metadata().data())
                .contains("reopen_reason").contains("regression on main").contains("triage-bot");
        int reopened = dsl.fetchCount(TASK_EVENTS,
                TASK_EVENTS.TASK_ID.eq(id).and(TASK_EVENTS.EVENT_TYPE.eq("task_reopened")));
        assertThat(reopened).isEqualTo(1);
    }

    @Test
    void cancelled_task_cannot_be_reopened() {
        UUID id = service.createGraph(
                List.of(new TaskDef("a", "A", null, "dev", null, null, null)),
                List.of()).get(0).id();
        repo.transitionStatus(id, TaskStatus.READY, TaskStatus.CANCELLED);

        boolean ok = service.reopenTask(id, "user changed mind", "operator");
        assertThat(ok).isFalse();
        assertThat(repo.findById(id).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void createTriageTask_lands_on_ready_with_metadata() {
        UUID failed = service.createGraph(
                List.of(new TaskDef("f", "Failed thing", null, "dev", null, null, null)),
                List.of()).get(0).id();

        Task triage = service.createTriageTask("regression", List.of(failed),
                "Build broke after merging task " + failed);
        assertThat(triage.role()).isEqualTo("triage");
        assertThat(triage.status()).isEqualTo(TaskStatus.READY);  // dep-free → ready
        assertThat(triage.priority()).isEqualTo(10);
        assertThat(triage.metadata().data())
                .contains("triage_kind").contains("regression").contains("related_task_ids");
    }
}
