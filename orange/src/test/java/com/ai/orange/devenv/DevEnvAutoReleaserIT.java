package com.ai.orange.devenv;

import static com.ai.orange.db.jooq.Tables.DEV_ENVS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Verifies that {@link DevEnvAutoReleaser} returns leases to the pool when no run of the holder
 * task can be using them any more, leaves active leases alone, and ignores non-task holders.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DevEnvAutoReleaserIT {

    @Autowired DevEnvAutoReleaser releaser;
    @Autowired DevEnvService envService;
    @Autowired DevEnvRepository envs;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired DSLContext dsl;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @DynamicPropertySource
    static void disableScheduler(DynamicPropertyRegistry r) {
        // Push the scheduled tick far out — the test triggers sweepOnce()
        // synchronously to assert behavior without racing the scheduler.
        r.add("orange.envs.auto-release-initial-delay", () -> "1h");
        r.add("orange.envs.auto-release-delay", () -> "1h");
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
        dsl.update(DEV_ENVS)
                .set(DEV_ENVS.STATUS, DevEnvStatus.FREE.dbValue())
                .setNull(DEV_ENVS.LEASED_BY)
                .setNull(DEV_ENVS.LEASED_AT)
                .execute();
    }

    @Test
    void releases_lease_when_task_reaches_in_review() throws Exception {
        UUID taskId = createTask();
        DevEnv leased = envService.acquire("task-" + taskId, Duration.ofSeconds(2)).orElseThrow();
        assertThat(envs.findLeased()).hasSize(1);

        // The task moved on to review but its holder never released the env.
        tasks.transitionStatus(taskId, TaskStatus.READY, TaskStatus.RUNNING);
        tasks.transitionStatus(taskId, TaskStatus.RUNNING, TaskStatus.IN_REVIEW);

        int released = releaser.sweepOnce();

        assertThat(released).isEqualTo(1);
        assertThat(envs.findLeased()).isEmpty();
        Optional<DevEnv> e = envs.findById(leased.id());
        assertThat(e.orElseThrow().status()).isEqualTo(DevEnvStatus.FREE);

        JSONB payload = dsl.select(TASK_EVENTS.PAYLOAD).from(TASK_EVENTS)
                .where(TASK_EVENTS.TASK_ID.eq(taskId)
                        .and(TASK_EVENTS.EVENT_TYPE.eq("env_auto_released")))
                .fetchOne(0, JSONB.class);
        JsonNode node = MAPPER.readTree(payload.data());
        assertThat(node.path("reason").asText()).isEqualTo("in_review");
    }

    @Test
    void leaves_lease_alone_while_task_is_running() throws Exception {
        UUID taskId = createTask();
        envService.acquire("task-" + taskId, Duration.ofSeconds(2));
        tasks.transitionStatus(taskId, TaskStatus.READY, TaskStatus.RUNNING);

        int released = releaser.sweepOnce();

        assertThat(released).isZero();
        assertThat(envs.findLeased()).hasSize(1);
    }

    @Test
    void ignores_non_task_holder() throws Exception {
        envService.acquire("manual-operator", Duration.ofSeconds(2));
        assertThat(envs.findLeased()).hasSize(1);

        int released = releaser.sweepOnce();

        assertThat(released).isZero();
        assertThat(envs.findLeased()).hasSize(1);
    }

    @Test
    void reclaims_lease_when_task_row_was_deleted() throws Exception {
        UUID taskId = createTask();
        envService.acquire("task-" + taskId, Duration.ofSeconds(2));
        // Operator nuked the task. The env would otherwise stay leased forever.
        dsl.deleteFrom(TASKS).where(TASKS.ID.eq(taskId)).execute();

        int released = releaser.sweepOnce();

        assertThat(released).isEqualTo(1);
        assertThat(envs.findLeased()).isEmpty();
    }

    @Test
    void release_is_idempotent() throws Exception {
        UUID taskId = createTask();
        envService.acquire("task-" + taskId, Duration.ofSeconds(2));
        tasks.transitionStatus(taskId, TaskStatus.READY, TaskStatus.CANCELLED);

        assertThat(releaser.sweepOnce()).isEqualTo(1);
        // Second sweep: env is already FREE so the LEASED-only filter in
        // DevEnvRepository.release() means no work + no duplicated event.
        assertThat(releaser.sweepOnce()).isZero();

        int events = dsl.fetchCount(TASK_EVENTS,
                TASK_EVENTS.TASK_ID.eq(taskId).and(TASK_EVENTS.EVENT_TYPE.eq("env_auto_released")));
        assertThat(events).isEqualTo(1);
    }

    @Test
    void parseTaskHolder_round_trips_uuid() {
        UUID id = UUID.randomUUID();
        assertThat(DevEnvAutoReleaser.parseTaskHolder("task-" + id)).isEqualTo(id);
        assertThat(DevEnvAutoReleaser.parseTaskHolder("manual")).isNull();
        assertThat(DevEnvAutoReleaser.parseTaskHolder("task-not-a-uuid")).isNull();
        assertThat(DevEnvAutoReleaser.parseTaskHolder(null)).isNull();
    }

    private UUID createTask() {
        List<Task> created = taskService.createGraph(
                List.of(new TaskDef("t-" + UUID.randomUUID(), "T", null, "implementer", null, null)),
                List.of());
        return created.get(0).id();
    }
}
