package com.ai.orange.workflow;

import static com.ai.orange.db.jooq.Tables.DEV_ENVS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.devenv.DevEnvStatus;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Exercises the lease half of auto-env-prep without involving an agent
 * subprocess or Temporal. Verifies {@link PipelineActivitiesImpl#acquireDevEnv}
 * leases an env from the pool, ports parse out of the JSONB column, the
 * {@code env_acquired} event lands on {@code task_events}, and
 * {@code releaseDevEnv} returns it to the {@code FREE} pool.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class DevEnvActivitiesIT {

    @Autowired PipelineActivitiesImpl activities;
    @Autowired TaskService taskService;
    @Autowired DSLContext dsl;

    @DynamicPropertySource
    static void noAutoStart(DynamicPropertyRegistry r) {
        r.add("orange.workflow.auto-start", () -> "false");
        // Keep acquire-timeout small so a starvation test returns quickly.
        r.add("orange.envs.acquire-timeout", () -> "2s");
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
        // Reset any leased envs from prior tests in this class.
        dsl.update(DEV_ENVS)
                .set(DEV_ENVS.STATUS, DevEnvStatus.FREE.dbValue())
                .setNull(DEV_ENVS.LEASED_BY)
                .setNull(DEV_ENVS.LEASED_AT)
                .execute();
    }

    @Test
    void acquire_returns_env_with_parsed_ports_then_release_frees_it() {
        UUID taskId = taskService.createGraph(
                List.of(new TaskDef("t", "T", null, "tester", null, null, null)),
                List.of()).get(0).id();

        EnvInfo env = activities.acquireDevEnv(taskId);
        assertThat(env).isNotNull();
        assertThat(env.envId()).isNotNull();
        assertThat(env.name()).startsWith("env-");
        // V3 seed: every env has web/api/db ports.
        assertThat(env.ports()).containsKeys("web", "api", "db");
        assertThat(env.ports().get("web")).isBetween(3000, 3010);
        // V8 seeds per-env metadata.
        assertThat(env.dataDir()).startsWith("/var/orange/envs/").contains(env.name());
        assertThat(env.authBypassToken()).startsWith("env-").hasSize(16);
        assertThat(env.fixtureSet()).isEqualTo("default");

        int leasedCount = dsl.fetchCount(DEV_ENVS, DEV_ENVS.STATUS.eq(DevEnvStatus.LEASED.dbValue()));
        assertThat(leasedCount).isEqualTo(1);

        // env_acquired event landed on the task's stream.
        int acquiredEvents = dsl.fetchCount(TASK_EVENTS,
                TASK_EVENTS.TASK_ID.eq(taskId).and(TASK_EVENTS.EVENT_TYPE.eq("env_acquired")));
        assertThat(acquiredEvents).isEqualTo(1);

        activities.releaseDevEnv(env);
        int stillLeased = dsl.fetchCount(DEV_ENVS, DEV_ENVS.STATUS.eq(DevEnvStatus.LEASED.dbValue()));
        assertThat(stillLeased).isZero();
    }

    @Test
    void acquire_times_out_when_pool_exhausted() {
        // V3 seeds 5 envs. Lease them all.
        UUID taskId = taskService.createGraph(
                List.of(new TaskDef("t", "T", null, "tester", null, null, null)),
                List.of()).get(0).id();
        for (int i = 0; i < 5; i++) {
            activities.acquireDevEnv(taskId);
        }
        // 6th acquire has nothing left — fails after the 2s configured timeout.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> activities.acquireDevEnv(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("acquire timed out");
    }
}
