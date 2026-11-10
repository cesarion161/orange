package com.ai.orange.session;

import static com.ai.orange.db.jooq.Tables.VERDICTS;

import com.ai.orange.db.jooq.tables.records.VerdictsRecord;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class VerdictRepository {

    private final DSLContext dsl;

    public VerdictRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Records the verdict for a stop; empty when that stop already has one. */
    public Optional<Verdict> insert(UUID sessionId, UUID runId, String stopEventId, String kind, String message,
                                    String rationale, Double confidence, String status) {
        return dsl.insertInto(VERDICTS)
                .set(VERDICTS.SESSION_ID, sessionId)
                .set(VERDICTS.RUN_ID, runId)
                .set(VERDICTS.STOP_EVENT_ID, stopEventId)
                .set(VERDICTS.KIND, kind)
                .set(VERDICTS.MESSAGE, message)
                .set(VERDICTS.RATIONALE, rationale)
                .set(VERDICTS.CONFIDENCE, confidence == null ? null
                        : BigDecimal.valueOf(Math.clamp(confidence, 0.0, 1.0)).setScale(2, RoundingMode.HALF_UP))
                .set(VERDICTS.STATUS, status)
                .onConflictDoNothing()
                .returning().fetchOptional().map(VerdictRepository::toDomain);
    }

    public Optional<Verdict> find(UUID sessionId, String stopEventId) {
        return dsl.selectFrom(VERDICTS)
                .where(VERDICTS.SESSION_ID.eq(sessionId).and(VERDICTS.STOP_EVENT_ID.eq(stopEventId)))
                .fetchOptional().map(VerdictRepository::toDomain);
    }

    /** A session's verdicts, oldest first. */
    public List<Verdict> findBySession(UUID sessionId) {
        return dsl.selectFrom(VERDICTS).where(VERDICTS.SESSION_ID.eq(sessionId))
                .orderBy(VERDICTS.CREATED_AT.asc(), VERDICTS.ID.asc()).fetch().map(VerdictRepository::toDomain);
    }

    /** Pending verdicts made before {@code t}: no stop hook came for them. */
    public List<Verdict> findPendingBefore(OffsetDateTime t) {
        return dsl.selectFrom(VERDICTS)
                .where(VERDICTS.STATUS.eq(Verdict.PENDING).and(VERDICTS.CREATED_AT.lessThan(t)))
                .orderBy(VERDICTS.CREATED_AT.asc()).fetch().map(VerdictRepository::toDomain);
    }

    /** Moves a pending verdict on; false when it was no longer pending. */
    public boolean settle(UUID id, String status) {
        return dsl.update(VERDICTS)
                .set(VERDICTS.STATUS, status)
                .set(VERDICTS.DELIVERED_AT, Verdict.DELIVERED.equals(status) ? OffsetDateTime.now() : null)
                .where(VERDICTS.ID.eq(id).and(VERDICTS.STATUS.eq(Verdict.PENDING)))
                .execute() > 0;
    }

    /** The user spoke first: whatever was waiting for the hook is void. Returns how many. */
    public int cancelPending(UUID sessionId) {
        return dsl.update(VERDICTS).set(VERDICTS.STATUS, Verdict.CANCELLED)
                .where(VERDICTS.SESSION_ID.eq(sessionId).and(VERDICTS.STATUS.eq(Verdict.PENDING)))
                .execute();
    }

    static Verdict toDomain(VerdictsRecord r) {
        return new Verdict(r.getId(), r.getSessionId(), r.getRunId(), r.getStopEventId(), r.getKind(), r.getMessage(),
                r.getRationale(), r.getConfidence() == null ? null : r.getConfidence().doubleValue(), r.getStatus(),
                r.getCreatedAt());
    }
}
