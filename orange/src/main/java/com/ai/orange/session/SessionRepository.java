package com.ai.orange.session;

import static com.ai.orange.db.jooq.Tables.SESSIONS;
import static com.ai.orange.db.jooq.Tables.SESSION_EVENTS;

import com.ai.orange.db.jooq.tables.records.SessionsRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {

    private final DSLContext dsl;

    public SessionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Session> findById(UUID id) {
        return dsl.selectFrom(SESSIONS).where(SESSIONS.ID.eq(id)).fetchOptional().map(SessionRepository::toDomain);
    }

    /** The session with this harness id, locked for the caller's transaction. */
    public Optional<Session> lock(String harness, String externalId) {
        return dsl.selectFrom(SESSIONS)
                .where(SESSIONS.HARNESS.eq(harness).and(SESSIONS.EXTERNAL_ID.eq(externalId)))
                .forUpdate().fetchOptional().map(SessionRepository::toDomain);
    }

    public Optional<Session> find(String harness, String externalId) {
        return dsl.selectFrom(SESSIONS)
                .where(SESSIONS.HARNESS.eq(harness).and(SESSIONS.EXTERNAL_ID.eq(externalId)))
                .fetchOptional().map(SessionRepository::toDomain);
    }

    public Session insert(String harness, String externalId, String nodeId) {
        return toDomain(dsl.insertInto(SESSIONS)
                .set(SESSIONS.HARNESS, harness)
                .set(SESSIONS.EXTERNAL_ID, externalId)
                .set(SESSIONS.NODE_ID, nodeId)
                .returning().fetchOne());
    }

    /** Writes the whole mutable state of a session. */
    public void save(Session s) {
        dsl.update(SESSIONS)
                .set(SESSIONS.PROJECT_ID, s.projectId())
                .set(SESSIONS.NODE_ID, s.nodeId())
                .set(SESSIONS.CWD, s.cwd())
                .set(SESSIONS.REPO_ROOT, s.repoRoot())
                .set(SESSIONS.BRANCH, s.branch())
                .set(SESSIONS.WORKTREE, s.worktree())
                .set(SESSIONS.INTENT, s.intent())
                .set(SESSIONS.STATUS, s.status())
                .set(SESSIONS.WAITING_REASON, s.waitingReason())
                .set(SESSIONS.WAITING_SINCE, s.waitingSince())
                .set(SESSIONS.LAST_MESSAGE, s.lastMessage())
                .set(SESSIONS.AUTO_CONTINUES, s.autoContinues())
                .set(SESSIONS.LAST_EVENT_AT, s.lastEventAt())
                .set(SESSIONS.LAST_USER_PROMPT_AT, s.lastUserPromptAt())
                .set(SESSIONS.CLOSED_REASON, s.closedReason())
                .where(SESSIONS.ID.eq(s.id()))
                .execute();
    }

    public void addEvent(UUID sessionId, String type, JSONB payload, OffsetDateTime occurredAt) {
        dsl.insertInto(SESSION_EVENTS)
                .set(SESSION_EVENTS.SESSION_ID, sessionId)
                .set(SESSION_EVENTS.TYPE, type)
                .set(SESSION_EVENTS.PAYLOAD, payload)
                .set(SESSION_EVENTS.OCCURRED_AT, occurredAt)
                .execute();
    }

    public int countEvents(UUID sessionId) {
        return dsl.fetchCount(SESSION_EVENTS, SESSION_EVENTS.SESSION_ID.eq(sessionId));
    }

    /** Sessions that are waiting for someone, longest wait first. */
    public List<Session> findWaiting() {
        return dsl.selectFrom(SESSIONS).where(SESSIONS.WAITING_SINCE.isNotNull())
                .orderBy(SESSIONS.WAITING_SINCE.asc()).fetch().map(SessionRepository::toDomain);
    }

    /** Sessions, most recently heard from first; without the ended ones unless {@code includeEnded}. */
    public List<Session> findRecent(boolean includeEnded, UUID projectId, int limit) {
        Condition where = includeEnded ? DSL.noCondition() : SESSIONS.STATUS.ne(Session.ENDED);
        if (projectId != null) where = where.and(SESSIONS.PROJECT_ID.eq(projectId));
        return dsl.selectFrom(SESSIONS).where(where)
                .orderBy(SESSIONS.LAST_EVENT_AT.desc(), SESSIONS.ID.desc()).limit(limit)
                .fetch().map(SessionRepository::toDomain);
    }

    /** Gives a repo's sessions to the project that now owns the repo. */
    public int moveToProject(String nodeId, String repoRoot, UUID projectId) {
        return dsl.update(SESSIONS).set(SESSIONS.PROJECT_ID, projectId)
                .where(SESSIONS.NODE_ID.eq(nodeId).and(SESSIONS.REPO_ROOT.eq(repoRoot)))
                .execute();
    }

    static Session toDomain(SessionsRecord r) {
        return new Session(r.getId(), r.getProjectId(), r.getHarness(), r.getExternalId(), r.getNodeId(), r.getCwd(),
                r.getRepoRoot(), r.getBranch(), r.getWorktree(), r.getIntent(), r.getStatus(), r.getWaitingReason(),
                r.getWaitingSince(), r.getLastMessage(), r.getAutoContinues(), r.getLastEventAt(),
                r.getLastUserPromptAt(), r.getClosedReason(), r.getCreatedAt());
    }
}
