package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.RUN_EVENTS;

import com.ai.orange.db.jooq.tables.records.RunEventsRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class RunEventRepository {

    private static final int MAX_PAGE = 500;

    private final DSLContext dsl;

    public RunEventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public void append(UUID runId, int seq, String type, JSONB payload) {
        dsl.insertInto(RUN_EVENTS)
                .set(RUN_EVENTS.RUN_ID, runId)
                .set(RUN_EVENTS.SEQ, seq)
                .set(RUN_EVENTS.TYPE, type)
                .set(RUN_EVENTS.PAYLOAD, payload == null ? JSONB.valueOf("{}") : payload)
                .execute();
    }

    /** Events of {@code runId} with {@code seq > afterSeq}, oldest first; the limit is clamped to 500. */
    public List<RunEventRow> findByRunSince(UUID runId, int afterSeq, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_PAGE);
        return dsl.selectFrom(RUN_EVENTS)
                .where(RUN_EVENTS.RUN_ID.eq(runId).and(RUN_EVENTS.SEQ.gt(afterSeq)))
                .orderBy(RUN_EVENTS.SEQ.asc())
                .limit(safeLimit)
                .fetch()
                .map(RunEventRepository::toDomain);
    }

    /** The latest event of {@code type} in {@code runId}, e.g. a run's last assistant message. */
    public Optional<RunEventRow> findLastByType(UUID runId, String type) {
        return dsl.selectFrom(RUN_EVENTS)
                .where(RUN_EVENTS.RUN_ID.eq(runId).and(RUN_EVENTS.TYPE.eq(type)))
                .orderBy(RUN_EVENTS.SEQ.desc())
                .limit(1)
                .fetchOptional()
                .map(RunEventRepository::toDomain);
    }

    static RunEventRow toDomain(RunEventsRecord r) {
        return new RunEventRow(r.getId(), r.getRunId(), r.getSeq(), r.getType(), r.getPayload(), r.getCreatedAt());
    }
}
