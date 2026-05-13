package com.ai.orange.task;

import static com.ai.orange.db.jooq.Tables.TASK_CLAIMS;

import com.ai.orange.db.jooq.tables.records.TaskClaimsRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class TaskClaimRepository {

    private final DSLContext dsl;

    public TaskClaimRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Insert a new active claim for {@code taskId}. The {@code task_claims}
     * primary key on {@code task_id} guarantees only one live lease per task —
     * a concurrent insert raises a unique-constraint violation, which is the
     * intended contention semantics (the caller already won the race in
     * {@code tasks} via FOR UPDATE SKIP LOCKED).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public TaskClaim insert(UUID taskId, UUID claimToken, String workerId,
                            int attempt, OffsetDateTime expiresAt) {
        TaskClaimsRecord rec = dsl.insertInto(TASK_CLAIMS)
                .set(TASK_CLAIMS.TASK_ID, taskId)
                .set(TASK_CLAIMS.CLAIM_TOKEN, claimToken)
                .set(TASK_CLAIMS.WORKER_ID, workerId)
                .set(TASK_CLAIMS.ATTEMPT, attempt)
                .set(TASK_CLAIMS.EXPIRES_AT, expiresAt)
                .returning()
                .fetchOne();
        return toDomain(rec);
    }

    public Optional<TaskClaim> findByToken(UUID token) {
        return dsl.selectFrom(TASK_CLAIMS)
                .where(TASK_CLAIMS.CLAIM_TOKEN.eq(token))
                .fetchOptional()
                .map(TaskClaimRepository::toDomain);
    }

    public Optional<TaskClaim> findByTask(UUID taskId) {
        return dsl.selectFrom(TASK_CLAIMS)
                .where(TASK_CLAIMS.TASK_ID.eq(taskId))
                .fetchOptional()
                .map(TaskClaimRepository::toDomain);
    }

    /**
     * Bump {@code expires_at}. Returns the refreshed claim if the token still
     * matches the row, empty if the claim was reaped/released. The lease
     * keeps the same {@code claim_token} so the caller's session need not
     * rotate state.
     */
    @Transactional
    public Optional<TaskClaim> heartbeat(UUID token, OffsetDateTime newExpiresAt) {
        TaskClaimsRecord rec = dsl.update(TASK_CLAIMS)
                .set(TASK_CLAIMS.EXPIRES_AT, newExpiresAt)
                .where(TASK_CLAIMS.CLAIM_TOKEN.eq(token))
                .returning()
                .fetchOne();
        return Optional.ofNullable(rec).map(TaskClaimRepository::toDomain);
    }

    public boolean requestCancel(UUID taskId) {
        return dsl.update(TASK_CLAIMS)
                .set(TASK_CLAIMS.CANCEL_REQUESTED, true)
                .where(TASK_CLAIMS.TASK_ID.eq(taskId))
                .execute() > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deleteByToken(UUID token) {
        return dsl.deleteFrom(TASK_CLAIMS)
                .where(TASK_CLAIMS.CLAIM_TOKEN.eq(token))
                .execute() > 0;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean deleteByTask(UUID taskId) {
        return dsl.deleteFrom(TASK_CLAIMS)
                .where(TASK_CLAIMS.TASK_ID.eq(taskId))
                .execute() > 0;
    }

    /** Claims whose {@code expires_at} is before {@code threshold}. */
    public List<TaskClaim> findExpired(OffsetDateTime threshold) {
        return dsl.selectFrom(TASK_CLAIMS)
                .where(TASK_CLAIMS.EXPIRES_AT.lessThan(threshold))
                .orderBy(TASK_CLAIMS.EXPIRES_AT.asc())
                .fetch()
                .map(TaskClaimRepository::toDomain);
    }

    public List<TaskClaim> findByWorker(String workerId) {
        return dsl.selectFrom(TASK_CLAIMS)
                .where(TASK_CLAIMS.WORKER_ID.eq(workerId))
                .orderBy(TASK_CLAIMS.ISSUED_AT.asc())
                .fetch()
                .map(TaskClaimRepository::toDomain);
    }

    static TaskClaim toDomain(TaskClaimsRecord r) {
        return new TaskClaim(
                r.getTaskId(),
                r.getClaimToken(),
                r.getWorkerId(),
                r.getIssuedAt(),
                r.getExpiresAt(),
                Boolean.TRUE.equals(r.getCancelRequested()),
                r.getAttempt() == null ? 1 : r.getAttempt(),
                r.getMetadata());
    }
}
