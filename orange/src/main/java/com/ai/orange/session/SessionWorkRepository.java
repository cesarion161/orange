package com.ai.orange.session;

import static com.ai.orange.db.jooq.Tables.SESSION_WORK;

import com.ai.orange.db.jooq.tables.records.SessionWorkRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class SessionWorkRepository {

    private final DSLContext dsl;

    public SessionWorkRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Opens work for the session unless it has some open; true when this call opened it. */
    public boolean openIfNone(UUID sessionId, String intent) {
        return dsl.insertInto(SESSION_WORK)
                .set(SESSION_WORK.SESSION_ID, sessionId)
                .set(SESSION_WORK.INTENT, intent)
                .onConflict(SESSION_WORK.SESSION_ID).where(SESSION_WORK.STATUS.eq(SessionWork.OPEN))
                .doNothing()
                .execute() > 0;
    }

    /** Closes the session's open work; empty when it has none. */
    public Optional<SessionWork> close(UUID sessionId, String result, String headSha, String branch, String baseBranch,
                                       String note) {
        return dsl.update(SESSION_WORK)
                .set(SESSION_WORK.STATUS, SessionWork.CLOSED)
                .set(SESSION_WORK.RESULT, result)
                .set(SESSION_WORK.HEAD_SHA, headSha)
                .set(SESSION_WORK.BRANCH, branch)
                .set(SESSION_WORK.BASE_BRANCH, baseBranch)
                .set(SESSION_WORK.NOTE, note)
                .set(SESSION_WORK.CLOSED_AT, OffsetDateTime.now())
                .where(SESSION_WORK.SESSION_ID.eq(sessionId).and(SESSION_WORK.STATUS.eq(SessionWork.OPEN)))
                .returning()
                .fetchOptional().map(SessionWorkRepository::toDomain);
    }

    /** A session's work, newest first. */
    public List<SessionWork> findBySession(UUID sessionId) {
        return dsl.selectFrom(SESSION_WORK).where(SESSION_WORK.SESSION_ID.eq(sessionId))
                .orderBy(SESSION_WORK.OPENED_AT.desc(), SESSION_WORK.ID.desc())
                .fetch().map(SessionWorkRepository::toDomain);
    }

    static SessionWork toDomain(SessionWorkRecord r) {
        return new SessionWork(r.getId(), r.getSessionId(), r.getIntent(), r.getStatus(), r.getResult(), r.getHeadSha(),
                r.getBranch(), r.getBaseBranch(), r.getNote(), r.getOpenedAt(), r.getClosedAt());
    }
}
