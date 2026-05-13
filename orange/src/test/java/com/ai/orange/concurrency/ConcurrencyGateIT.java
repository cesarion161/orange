package com.ai.orange.concurrency;

import static com.ai.orange.db.jooq.Tables.AGENT_RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_CLAIMS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.concurrency.ConcurrencyGate.RoleUsage;
import com.ai.orange.task.ClaimService;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

/**
 * Exercises {@link ConcurrencyGate} via {@link ClaimService.claimNext}:
 * once {@code orange.concurrency.per-role.dev=2} is set, the third dev claim
 * is refused even though the queue has tasks. Other roles stay unaffected.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "orange.concurrency.per-role.dev=2",
        "orange.workflow.auto-start=false"
})
class ConcurrencyGateIT {

    @Autowired ClaimService claims;
    @Autowired TaskService taskService;
    @Autowired ConcurrencyGate gate;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_CLAIMS).execute();
        dsl.deleteFrom(AGENT_RUNS).execute();
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void per_role_cap_refuses_third_claim() {
        taskService.createGraph(List.of(
                new TaskDef("a", "A", null, "dev", null, null, null),
                new TaskDef("b", "B", null, "dev", null, null, null),
                new TaskDef("c", "C", null, "dev", null, null, null)),
                List.of());

        assertThat(claims.claimNext("dev", "w1")).isPresent();
        assertThat(claims.claimNext("dev", "w2")).isPresent();
        // Cap is 2; a ready task remains but the gate refuses.
        assertThat(claims.claimNext("dev", "w3")).isEmpty();

        // Releasing one slot frees up a claim.
        var second = claims.activeClaims("w2").get(0);
        claims.release(second.claimToken(), "test");
        assertThat(claims.claimNext("dev", "w3")).isPresent();
    }

    @Test
    void cap_does_not_bleed_across_roles() {
        taskService.createGraph(List.of(
                new TaskDef("a", "A", null, "dev", null, null, null),
                new TaskDef("b", "B", null, "dev", null, null, null),
                new TaskDef("t", "T", null, "tester", null, null, null)),
                List.of());

        claims.claimNext("dev", "w1");
        claims.claimNext("dev", "w2");
        // dev at cap, tester unset
        assertThat(claims.claimNext("tester", "wt")).isPresent();
    }

    @Test
    void usage_snapshot_reflects_in_flight_per_role() {
        taskService.createGraph(List.of(
                new TaskDef("a", "A", null, "dev", null, null, null),
                new TaskDef("t", "T", null, "tester", null, null, null)),
                List.of());
        claims.claimNext("dev", "w1");
        claims.claimNext("tester", "wt");

        Map<String, RoleUsage> usage = gate.usage();
        assertThat(usage.get("dev").inProgress()).isEqualTo(1);
        assertThat(usage.get("dev").cap()).isEqualTo(2);
        assertThat(usage.get("dev").atCap()).isFalse();
        assertThat(usage.get("tester").inProgress()).isEqualTo(1);
        assertThat(usage.get("tester").cap()).isNull();
        assertThat(usage.get("tester").atCap()).isFalse();
        // Baseline roles surface even with zero work.
        assertThat(usage).containsKeys("reviewer", "planner");
    }
}
