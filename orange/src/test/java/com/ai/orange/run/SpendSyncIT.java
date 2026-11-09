package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.run.RunTestSupport.runningTask;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.db.jooq.tables.records.RunsRecord;
import com.ai.orange.gateway.GatewayClient;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** LiteLLM books spend after a run ends; spend sync brings the run's cost up to date, then deletes the key. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SpendSyncIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("spend-sync-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("spend-sync-it-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
    }

    @MockitoBean GatewayClient gateway;
    @Autowired SpendSync spendSync;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        reset(gateway);
        dsl.deleteFrom(RUNS).execute();
    }

    /** A finished run; a gateway run when {@code alias} is set. */
    Run finished(String alias, String cost, OffsetDateTime finishedAt) {
        Run run = runs.insertRunning(RunKind.IMPLEMENT, "implementer", runningTask(taskService, tasks, "x").id(),
                props.workerId(), props.leaseTtl(), JSONB.valueOf("{}"));
        runs.recordRoute(run.id(), "medium", "claude-sonnet-5-5", "high", "claude-code",
                alias == null ? "subscription" : "gateway", new BigDecimal("4"), alias, null, null);
        runs.finish(run.id(), RunStatus.SUCCEEDED, null, new BigDecimal(cost), 0, 0, 0, null);
        dsl.update(RUNS).set(RUNS.FINISHED_AT, finishedAt).where(RUNS.ID.eq(run.id())).execute();
        return run;
    }

    RunsRecord row(Run run) {
        return dsl.selectFrom(RUNS).where(RUNS.ID.eq(run.id())).fetchSingle();
    }

    @Test
    void changedSpendIsRecorded() {
        Run run = finished("run-changed", "0.0100", OffsetDateTime.now().minusMinutes(1));
        when(gateway.spendByAlias("run-changed")).thenReturn(Optional.of(new BigDecimal("0.0731")));

        spendSync.sync();

        RunsRecord after = row(run);
        assertThat(after.getCostUsd()).isEqualByComparingTo("0.0731");
        assertThat(after.getSpendSyncedAt()).isNotNull();
        assertThat(after.getGatewayKeyDeletedAt()).isNull();
        verify(gateway, never()).deleteByAlias(anyString());
    }

    @Test
    void stableSpendDeletesTheKeyAfterTenMinutes() {
        Run old = finished("run-settled", "0.0500", OffsetDateTime.now().minusMinutes(11));
        Run recent = finished("run-recent", "0.0500", OffsetDateTime.now().minusMinutes(2));
        when(gateway.spendByAlias("run-settled")).thenReturn(Optional.of(new BigDecimal("0.05")));
        when(gateway.spendByAlias("run-recent")).thenReturn(Optional.of(new BigDecimal("0.05")));

        spendSync.sync();

        verify(gateway).deleteByAlias("run-settled");
        verify(gateway, never()).deleteByAlias("run-recent");
        assertThat(row(old).getGatewayKeyDeletedAt()).isNotNull();
        assertThat(row(recent).getGatewayKeyDeletedAt()).isNull();

        reset(gateway);
        when(gateway.spendByAlias("run-recent")).thenReturn(Optional.of(new BigDecimal("0.05")));
        spendSync.sync();
        verify(gateway, never()).spendByAlias("run-settled");
    }

    @Test
    void aKeyLiteLlmNoLongerHasIsMarkedDeleted() {
        Run run = finished("run-vanished", "0.0200", OffsetDateTime.now().minusMinutes(1));
        when(gateway.spendByAlias("run-vanished")).thenReturn(Optional.empty());

        spendSync.sync();

        assertThat(row(run).getGatewayKeyDeletedAt()).isNotNull();
        assertThat(row(run).getCostUsd()).as("the cost at finish stands").isEqualByComparingTo("0.02");
    }

    @Test
    void subscriptionRunsAreIgnored() {
        finished(null, "0.0100", OffsetDateTime.now().minusHours(1));
        runs.insertRunning(RunKind.IMPLEMENT, "implementer", runningTask(taskService, tasks, "y").id(),
                props.workerId(), props.leaseTtl(), JSONB.valueOf("{}"));

        spendSync.sync();

        verify(gateway, never()).spendByAlias(anyString());
        verify(gateway, never()).deleteByAlias(anyString());
    }
}
