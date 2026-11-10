package com.ai.orange.attention;

import static com.ai.orange.db.jooq.Tables.ATTENTION_ITEMS;

import com.ai.orange.db.jooq.tables.records.AttentionItemsRecord;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

@Repository
public class AttentionRepository {

    private final DSLContext dsl;

    public AttentionRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Files an item; empty when one with this dedupe key was ever filed. */
    public Optional<AttentionItem> insert(String kind, String dedupeKey, String urgency, UUID projectId, UUID sessionId,
                                          UUID taskId, String title, String body, String proposedAnswer) {
        return dsl.insertInto(ATTENTION_ITEMS)
                .set(ATTENTION_ITEMS.KIND, kind)
                .set(ATTENTION_ITEMS.DEDUPE_KEY, dedupeKey)
                .set(ATTENTION_ITEMS.URGENCY, urgency)
                .set(ATTENTION_ITEMS.PROJECT_ID, projectId)
                .set(ATTENTION_ITEMS.SESSION_ID, sessionId)
                .set(ATTENTION_ITEMS.TASK_ID, taskId)
                .set(ATTENTION_ITEMS.TITLE, title)
                .set(ATTENTION_ITEMS.BODY, body == null ? "" : body)
                .set(ATTENTION_ITEMS.PROPOSED_ANSWER, proposedAnswer)
                .onConflictDoNothing()
                .returning().fetchOptional().map(AttentionRepository::toDomain);
    }

    public Optional<AttentionItem> findById(UUID id) {
        return dsl.selectFrom(ATTENTION_ITEMS).where(ATTENTION_ITEMS.ID.eq(id)).fetchOptional()
                .map(AttentionRepository::toDomain);
    }

    public Optional<AttentionItem> findByKey(String dedupeKey) {
        return dsl.selectFrom(ATTENTION_ITEMS).where(ATTENTION_ITEMS.DEDUPE_KEY.eq(dedupeKey)).fetchOptional()
                .map(AttentionRepository::toDomain);
    }

    /** Whether the project has an item of this kind that is not resolved yet (open or acked). */
    public boolean hasUnresolved(UUID projectId, String kind) {
        return dsl.fetchExists(ATTENTION_ITEMS, ATTENTION_ITEMS.PROJECT_ID.eq(projectId)
                .and(ATTENTION_ITEMS.KIND.eq(kind))
                .and(ATTENTION_ITEMS.STATUS.ne(AttentionItem.RESOLVED)));
    }

    /** Items by status (null = any), most urgent first, then oldest first. */
    public List<AttentionItem> find(String status, int limit) {
        Condition where = status == null ? DSL.noCondition() : ATTENTION_ITEMS.STATUS.eq(status);
        return dsl.selectFrom(ATTENTION_ITEMS).where(where)
                .orderBy(DSL.case_(ATTENTION_ITEMS.URGENCY).when("critical", 0).when("urgent", 1).else_(2).asc(),
                        ATTENTION_ITEMS.CREATED_AT.asc(), ATTENTION_ITEMS.ID.asc())
                .limit(limit).fetch().map(AttentionRepository::toDomain);
    }

    public List<AttentionItem> findBySession(UUID sessionId) {
        return dsl.selectFrom(ATTENTION_ITEMS).where(ATTENTION_ITEMS.SESSION_ID.eq(sessionId))
                .orderBy(ATTENTION_ITEMS.CREATED_AT.asc(), ATTENTION_ITEMS.ID.asc())
                .fetch().map(AttentionRepository::toDomain);
    }

    /** Open, unpushed items the notifier may push: urgent and critical, oldest first. */
    public List<AttentionItem> findUnpushed() {
        return dsl.selectFrom(ATTENTION_ITEMS)
                .where(ATTENTION_ITEMS.STATUS.eq(AttentionItem.OPEN)
                        .and(ATTENTION_ITEMS.PUSHED_AT.isNull())
                        .and(ATTENTION_ITEMS.URGENCY.in(AttentionItem.URGENT, AttentionItem.CRITICAL)))
                .orderBy(ATTENTION_ITEMS.CREATED_AT.asc(), ATTENTION_ITEMS.ID.asc())
                .fetch().map(AttentionRepository::toDomain);
    }

    /** When the last batched (non-critical) push went out. */
    public Optional<OffsetDateTime> lastBatchedPushAt() {
        return Optional.ofNullable(dsl.select(DSL.max(ATTENTION_ITEMS.PUSHED_AT)).from(ATTENTION_ITEMS)
                .where(ATTENTION_ITEMS.URGENCY.ne(AttentionItem.CRITICAL)).fetchOne(0, OffsetDateTime.class));
    }

    public void markPushed(Collection<UUID> ids, OffsetDateTime at) {
        if (ids.isEmpty()) return;
        dsl.update(ATTENTION_ITEMS).set(ATTENTION_ITEMS.PUSHED_AT, at).where(ATTENTION_ITEMS.ID.in(ids)).execute();
    }

    /** Raises a session's open {@code normal} items of these kinds to {@code urgency}; the number raised. */
    public int escalateForSession(UUID sessionId, Collection<String> kinds, String urgency) {
        return dsl.update(ATTENTION_ITEMS)
                .set(ATTENTION_ITEMS.URGENCY, urgency)
                .where(ATTENTION_ITEMS.SESSION_ID.eq(sessionId)
                        .and(ATTENTION_ITEMS.KIND.in(kinds))
                        .and(ATTENTION_ITEMS.STATUS.eq(AttentionItem.OPEN))
                        .and(ATTENTION_ITEMS.URGENCY.eq(AttentionItem.NORMAL)))
                .execute();
    }

    public boolean ack(UUID id) {
        return dsl.update(ATTENTION_ITEMS).set(ATTENTION_ITEMS.STATUS, AttentionItem.ACKED)
                .where(ATTENTION_ITEMS.ID.eq(id).and(ATTENTION_ITEMS.STATUS.eq(AttentionItem.OPEN)))
                .execute() > 0;
    }

    /** Resolves an item that is not resolved yet. */
    public boolean resolve(UUID id, String by, String resolution) {
        return resolveWhere(ATTENTION_ITEMS.ID.eq(id), by, resolution) > 0;
    }

    /** Resolves every unresolved item of a session; the number resolved. */
    public int resolveForSession(UUID sessionId, String by, String resolution) {
        return resolveWhere(ATTENTION_ITEMS.SESSION_ID.eq(sessionId), by, resolution);
    }

    public int resolveByKey(String dedupeKey, String by, String resolution) {
        return resolveWhere(ATTENTION_ITEMS.DEDUPE_KEY.eq(dedupeKey), by, resolution);
    }

    private int resolveWhere(Condition which, String by, String resolution) {
        return dsl.update(ATTENTION_ITEMS)
                .set(ATTENTION_ITEMS.STATUS, AttentionItem.RESOLVED)
                .set(ATTENTION_ITEMS.RESOLVED_BY, by)
                .set(ATTENTION_ITEMS.RESOLUTION, resolution)
                .set(ATTENTION_ITEMS.RESOLVED_AT, OffsetDateTime.now())
                .where(which.and(ATTENTION_ITEMS.STATUS.ne(AttentionItem.RESOLVED)))
                .execute();
    }

    static AttentionItem toDomain(AttentionItemsRecord r) {
        return new AttentionItem(r.getId(), r.getProjectId(), r.getSessionId(), r.getTaskId(), r.getKind(),
                r.getDedupeKey(), r.getUrgency(), r.getTitle(), r.getBody(), r.getProposedAnswer(), r.getStatus(),
                r.getPushedAt(), r.getResolvedBy(), r.getResolution(), r.getResolvedAt(), r.getCreatedAt());
    }
}
