package com.ai.orange.project;

import static com.ai.orange.db.jooq.Tables.SPACE_ITEMS;

import com.ai.orange.db.jooq.tables.records.SpaceItemsRecord;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class SpaceRepository {

    private final DSLContext dsl;

    public SpaceRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public SpaceItem insert(UUID projectId, UUID sessionId, String kind, String key, JSONB body, String status,
                            String authorKind, String authorRef, OffsetDateTime dueAt, UUID supersedesId) {
        return toDomain(dsl.insertInto(SPACE_ITEMS)
                .set(SPACE_ITEMS.PROJECT_ID, projectId)
                .set(SPACE_ITEMS.SESSION_ID, sessionId)
                .set(SPACE_ITEMS.KIND, kind)
                .set(SPACE_ITEMS.KEY, key)
                .set(SPACE_ITEMS.BODY, body)
                .set(SPACE_ITEMS.STATUS, status)
                .set(SPACE_ITEMS.AUTHOR_KIND, authorKind)
                .set(SPACE_ITEMS.AUTHOR_REF, authorRef)
                .set(SPACE_ITEMS.DUE_AT, dueAt)
                .set(SPACE_ITEMS.SUPERSEDES_ID, supersedesId)
                .returning().fetchOne());
    }

    public Optional<SpaceItem> findById(UUID id) {
        return dsl.selectFrom(SPACE_ITEMS).where(SPACE_ITEMS.ID.eq(id)).fetchOptional().map(SpaceRepository::toDomain);
    }

    /** A project's items in these statuses, oldest first. */
    public List<SpaceItem> find(UUID projectId, Collection<String> statuses) {
        return dsl.selectFrom(SPACE_ITEMS)
                .where(SPACE_ITEMS.PROJECT_ID.eq(projectId).and(SPACE_ITEMS.STATUS.in(statuses)))
                .orderBy(SPACE_ITEMS.CREATED_AT.asc(), SPACE_ITEMS.ID.asc())
                .fetch().map(SpaceRepository::toDomain);
    }

    /** The active value of a convention: a session's override when {@code sessionId} is given, else the project's. */
    public Optional<SpaceItem> activeConvention(UUID projectId, UUID sessionId, String key) {
        Condition scope = sessionId == null ? SPACE_ITEMS.SESSION_ID.isNull() : SPACE_ITEMS.SESSION_ID.eq(sessionId);
        return dsl.selectFrom(SPACE_ITEMS)
                .where(SPACE_ITEMS.PROJECT_ID.eq(projectId)
                        .and(SPACE_ITEMS.KIND.eq(SpaceItem.CONVENTION))
                        .and(SPACE_ITEMS.KEY.eq(key))
                        .and(SPACE_ITEMS.STATUS.eq(SpaceItem.ACTIVE))
                        .and(scope))
                .fetchOptional().map(SpaceRepository::toDomain);
    }

    /** Moves an item from one of {@code from} to {@code to}; false when it was in neither. */
    public boolean setStatus(UUID id, Collection<String> from, String to) {
        return dsl.update(SPACE_ITEMS).set(SPACE_ITEMS.STATUS, to)
                .where(SPACE_ITEMS.ID.eq(id).and(SPACE_ITEMS.STATUS.in(from)))
                .execute() > 0;
    }

    static SpaceItem toDomain(SpaceItemsRecord r) {
        return new SpaceItem(r.getId(), r.getProjectId(), r.getSessionId(), r.getTaskId(), r.getKind(), r.getKey(),
                r.getBody(), r.getStatus(), r.getAuthorKind(), r.getAuthorRef(), r.getDueAt(), r.getSupersedesId(),
                r.getCreatedAt());
    }
}
