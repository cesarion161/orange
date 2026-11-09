package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.RUNS;

import com.ai.orange.db.jooq.tables.records.RunsRecord;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link Run}s. Every state change is a single conditional UPDATE, so callers
 * never race: a claim, heartbeat, outcome or finish only lands when the run is in the state the
 * caller assumed, and reports whether it did.
 */
@Repository
public class RunRepository {

    private static final Condition ACTIVE = RUNS.STATUS.in(RunStatus.QUEUED.dbValue(), RunStatus.RUNNING.dbValue());

    private final DSLContext dsl;

    public RunRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Queues a run for {@code taskId}. Empty when the task already has a queued or running run
     * (the one-active-run invariant, enforced by a partial unique index).
     */
    public Optional<Run> queue(UUID taskId, RunKind kind, String role, int priority, JSONB input) {
        int attempt = 1 + dsl.fetchCount(RUNS, RUNS.TASK_ID.eq(taskId).and(RUNS.KIND.eq(kind.dbValue())));
        return dsl.insertInto(RUNS)
                .set(RUNS.TASK_ID, taskId)
                .set(RUNS.KIND, kind.dbValue())
                .set(RUNS.ROLE, role)
                .set(RUNS.PRIORITY, priority)
                .set(RUNS.ATTEMPT, attempt)
                .set(RUNS.INPUT, input == null ? JSONB.valueOf("{}") : input)
                .onConflict(RUNS.TASK_ID)
                .where(RUNS.TASK_ID.isNotNull().and(ACTIVE))
                .doNothing()
                .returning()
                .fetchOptional()
                .map(RunRepository::toDomain);
    }

    /** Inserts a run that is already running, for runs started directly rather than queued. */
    public Run insertRunning(RunKind kind, String role, UUID taskIdOrNull, String workerId, Duration leaseTtl,
                             JSONB input) {
        OffsetDateTime now = OffsetDateTime.now();
        int attempt = taskIdOrNull == null ? 1
                : 1 + dsl.fetchCount(RUNS, RUNS.TASK_ID.eq(taskIdOrNull).and(RUNS.KIND.eq(kind.dbValue())));
        return toDomain(dsl.insertInto(RUNS)
                .set(RUNS.TASK_ID, taskIdOrNull)
                .set(RUNS.KIND, kind.dbValue())
                .set(RUNS.STATUS, RunStatus.RUNNING.dbValue())
                .set(RUNS.ROLE, role)
                .set(RUNS.ATTEMPT, attempt)
                .set(RUNS.INPUT, input == null ? JSONB.valueOf("{}") : input)
                .set(RUNS.WORKER_ID, workerId)
                .set(RUNS.LEASE_EXPIRES_AT, now.plus(leaseTtl))
                .set(RUNS.STARTED_AT, now)
                .returning()
                .fetchOne());
    }

    /** Claims the next queued run of {@code kind} (lowest priority value, then oldest). */
    public Optional<Run> claimNext(RunKind kind, String workerId, Duration leaseTtl) {
        OffsetDateTime now = OffsetDateTime.now();
        // A scalar "= (subquery)" is evaluated once. "IN (subquery)" may be re-scanned, and with
        // SKIP LOCKED every re-scan can pick a different row, claiming several runs at once.
        return dsl.update(RUNS)
                .set(RUNS.STATUS, RunStatus.RUNNING.dbValue())
                .set(RUNS.WORKER_ID, workerId)
                .set(RUNS.LEASE_EXPIRES_AT, now.plus(leaseTtl))
                .set(RUNS.STARTED_AT, now)
                .where(RUNS.ID.eq(dsl.select(RUNS.ID).from(RUNS)
                        .where(RUNS.STATUS.eq(RunStatus.QUEUED.dbValue()).and(RUNS.KIND.eq(kind.dbValue())))
                        .orderBy(RUNS.PRIORITY.asc(), RUNS.QUEUED_AT.asc())
                        .limit(1)
                        .forUpdate()
                        .skipLocked()))
                .and(RUNS.STATUS.eq(RunStatus.QUEUED.dbValue()))
                .returning()
                .fetchOptional()
                .map(RunRepository::toDomain);
    }

    public void recordRoute(UUID id, String tier, String model, String effort, String harness, String auth,
                            BigDecimal budgetUsd, String gatewayKeyAlias, String mcpTokenHash, String logPath) {
        dsl.update(RUNS)
                .set(RUNS.TIER, tier)
                .set(RUNS.MODEL, model)
                .set(RUNS.EFFORT, effort)
                .set(RUNS.HARNESS, harness)
                .set(RUNS.AUTH, auth)
                .set(RUNS.BUDGET_USD, budgetUsd)
                .set(RUNS.GATEWAY_KEY_ALIAS, gatewayKeyAlias)
                .set(RUNS.MCP_TOKEN_HASH, mcpTokenHash)
                .set(RUNS.LOG_PATH, logPath)
                .where(RUNS.ID.eq(id))
                .execute();
    }

    public void recordPid(UUID id, long pid) {
        dsl.update(RUNS).set(RUNS.PID, pid).where(RUNS.ID.eq(id)).execute();
    }

    /** Extends the lease. False when the run is no longer running or belongs to another worker. */
    public boolean heartbeat(UUID id, String workerId, Duration leaseTtl) {
        return dsl.update(RUNS)
                .set(RUNS.LEASE_EXPIRES_AT, OffsetDateTime.now().plus(leaseTtl))
                .where(RUNS.ID.eq(id)
                        .and(RUNS.STATUS.eq(RunStatus.RUNNING.dbValue()))
                        .and(RUNS.WORKER_ID.eq(workerId)))
                .execute() > 0;
    }

    /** Stores what the agent reported. Only while the run is running; the last report wins. */
    public boolean recordOutcome(UUID id, JSONB outcome) {
        return dsl.update(RUNS)
                .set(RUNS.OUTCOME, outcome)
                .where(RUNS.ID.eq(id).and(RUNS.STATUS.eq(RunStatus.RUNNING.dbValue())))
                .execute() > 0;
    }

    /** Ends a running run. False when it was not running (already finished, reaped or cancelled). */
    public boolean finish(UUID id, RunStatus status, String error, BigDecimal costUsd, long tokensIn,
                          long tokensOut, long cacheReadTokens, String sessionId) {
        return dsl.update(RUNS)
                .set(RUNS.STATUS, status.dbValue())
                .set(RUNS.ERROR, error)
                .set(RUNS.COST_USD, costUsd == null ? BigDecimal.ZERO : costUsd)
                .set(RUNS.TOKENS_IN, tokensIn)
                .set(RUNS.TOKENS_OUT, tokensOut)
                .set(RUNS.CACHE_READ_TOKENS, cacheReadTokens)
                .set(RUNS.SESSION_ID, sessionId)
                .set(RUNS.FINISHED_AT, OffsetDateTime.now())
                .where(RUNS.ID.eq(id).and(RUNS.STATUS.eq(RunStatus.RUNNING.dbValue())))
                .execute() > 0;
    }

    /** Cancels the task's queued (not yet started) runs. Returns how many. */
    public int cancelQueued(UUID taskId) {
        return dsl.update(RUNS)
                .set(RUNS.STATUS, RunStatus.CANCELLED.dbValue())
                .set(RUNS.ERROR, "cancelled")
                .set(RUNS.FINISHED_AT, OffsetDateTime.now())
                .where(RUNS.TASK_ID.eq(taskId).and(RUNS.STATUS.eq(RunStatus.QUEUED.dbValue())))
                .execute();
    }

    public Optional<Run> findById(UUID id) {
        return dsl.selectFrom(RUNS).where(RUNS.ID.eq(id)).fetchOptional().map(RunRepository::toDomain);
    }

    /** All runs of a task, oldest first. */
    public List<Run> findByTask(UUID taskId) {
        return dsl.selectFrom(RUNS).where(RUNS.TASK_ID.eq(taskId))
                .orderBy(RUNS.QUEUED_AT.asc(), RUNS.ID.asc())
                .fetch().map(RunRepository::toDomain);
    }

    /** The task's most recently finished run (succeeded, failed or cancelled), if any. */
    public Optional<Run> findLastFinished(UUID taskId) {
        return dsl.selectFrom(RUNS)
                .where(RUNS.TASK_ID.eq(taskId).and(RUNS.STATUS.notIn(RunStatus.QUEUED.dbValue(),
                        RunStatus.RUNNING.dbValue())))
                .orderBy(RUNS.FINISHED_AT.desc().nullsLast(), RUNS.ID.desc())
                .limit(1)
                .fetchOptional().map(RunRepository::toDomain);
    }

    /**
     * Whether a run of {@code kind} for this head succeeded with {@code passed: true} in its
     * outcome — for verify runs, that every check passed on that head.
     */
    public boolean hasSucceeded(UUID taskId, RunKind kind, String headSha) {
        return dsl.fetchExists(RUNS, RUNS.TASK_ID.eq(taskId)
                .and(RUNS.KIND.eq(kind.dbValue()))
                .and(RUNS.STATUS.eq(RunStatus.SUCCEEDED.dbValue()))
                .and(DSL.condition("{0} ->> 'head_sha' = {1}", RUNS.INPUT, DSL.val(headSha)))
                .and(DSL.condition("({0} ->> 'passed')::boolean IS TRUE", RUNS.OUTCOME)));
    }

    public Optional<Run> findActiveByTask(UUID taskId) {
        return dsl.selectFrom(RUNS).where(RUNS.TASK_ID.eq(taskId).and(ACTIVE))
                .fetchOptional().map(RunRepository::toDomain);
    }

    /** The running run whose MCP token hashes to {@code tokenHash}, if any. */
    public Optional<Run> findRunningByTokenHash(String tokenHash) {
        return dsl.selectFrom(RUNS)
                .where(RUNS.MCP_TOKEN_HASH.eq(tokenHash).and(RUNS.STATUS.eq(RunStatus.RUNNING.dbValue())))
                .fetchOptional().map(RunRepository::toDomain);
    }

    /** Every running run, oldest start first. */
    public List<Run> findRunning() {
        return dsl.selectFrom(RUNS)
                .where(RUNS.STATUS.eq(RunStatus.RUNNING.dbValue()))
                .orderBy(RUNS.STARTED_AT.asc(), RUNS.ID.asc())
                .fetch().map(RunRepository::toDomain);
    }

    public List<Run> findExpiredLeases(OffsetDateTime now) {
        return dsl.selectFrom(RUNS)
                .where(RUNS.STATUS.eq(RunStatus.RUNNING.dbValue()).and(RUNS.LEASE_EXPIRES_AT.lessThan(now)))
                .orderBy(RUNS.LEASE_EXPIRES_AT.asc())
                .fetch().map(RunRepository::toDomain);
    }

    /** Running runs another coordinator instance (e.g. the one before a restart) started. */
    public List<Run> findRunningNotOwnedBy(String workerId) {
        return dsl.selectFrom(RUNS)
                .where(RUNS.STATUS.eq(RunStatus.RUNNING.dbValue()).and(RUNS.WORKER_ID.isDistinctFrom(workerId)))
                .fetch().map(RunRepository::toDomain);
    }

    public int countByStatus(RunKind kind, RunStatus status) {
        return dsl.fetchCount(RUNS, RUNS.KIND.eq(kind.dbValue()).and(RUNS.STATUS.eq(status.dbValue())));
    }

    static Run toDomain(RunsRecord r) {
        return new Run(
                r.getId(),
                r.getTaskId(),
                RunKind.fromDb(r.getKind()),
                RunStatus.fromDb(r.getStatus()),
                r.getRole(),
                r.getPriority(),
                r.getAttempt(),
                r.getInput(),
                r.getOutcome(),
                r.getWorkerId(),
                r.getLeaseExpiresAt(),
                r.getPid(),
                r.getTier(),
                r.getModel(),
                r.getEffort(),
                r.getAuth(),
                r.getGatewayKeyAlias(),
                r.getCostUsd(),
                r.getError(),
                r.getQueuedAt(),
                r.getStartedAt(),
                r.getFinishedAt());
    }
}
