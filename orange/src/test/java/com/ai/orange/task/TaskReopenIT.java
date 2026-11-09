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
 * Operator retry on {@link TaskService#reopenTask}: a FAILED or NEEDS_HUMAN task goes back to
 * READY with its failure count reset; CANCELLED stays terminal.
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
        UUID id = newTask();
        repo.transitionStatus(id, TaskStatus.READY, TaskStatus.RUNNING);
        repo.transitionStatus(id, TaskStatus.RUNNING, TaskStatus.FAILED);

        boolean ok = service.reopenTask(id, "regression on main after merge", "operator");
        assertThat(ok).isTrue();

        Task reloaded = repo.findById(id).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(TaskStatus.READY);
        assertThat(reloaded.metadata().data())
                .contains("reopen_reason").contains("regression on main").contains("operator");
        int reopened = dsl.fetchCount(TASK_EVENTS,
                TASK_EVENTS.TASK_ID.eq(id).and(TASK_EVENTS.EVENT_TYPE.eq("task_reopened")));
        assertThat(reopened).isEqualTo(1);
    }

    @Test
    void needsHumanReopensWithFailuresReset() {
        UUID id = newTask();
        repo.transitionStatus(id, TaskStatus.READY, TaskStatus.RUNNING);
        repo.transitionStatus(id, TaskStatus.RUNNING, TaskStatus.NEEDS_HUMAN);
        dsl.update(TASKS).set(TASKS.FAILURES, 3).where(TASKS.ID.eq(id)).execute();

        assertThat(service.reopenTask(id, "try a smaller scope", "operator")).isTrue();

        Task reloaded = repo.findById(id).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(TaskStatus.READY);
        assertThat(reloaded.failures()).isZero();
    }

    @Test
    void cancelled_task_cannot_be_reopened() {
        UUID id = newTask();
        repo.transitionStatus(id, TaskStatus.READY, TaskStatus.CANCELLED);

        boolean ok = service.reopenTask(id, "user changed mind", "operator");
        assertThat(ok).isFalse();
        assertThat(repo.findById(id).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
    }

    private UUID newTask() {
        return service.createGraph(
                List.of(new TaskDef("a", "A", null, "implementer", null, null)),
                List.of()).get(0).id();
    }
}
