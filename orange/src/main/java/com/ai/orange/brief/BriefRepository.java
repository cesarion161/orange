package com.ai.orange.brief;

import static com.ai.orange.db.jooq.Tables.BRIEFS;

import com.ai.orange.db.jooq.tables.records.BriefsRecord;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class BriefRepository {

    private final DSLContext dsl;

    public BriefRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Writes a brief, complete; empty when its brief time ({@code slotAt}) already has one. */
    public Optional<Brief> insert(OffsetDateTime slotAt, OffsetDateTime snapshotAt, JSONB snapshot, JSONB content,
                                  String note, UUID runId, boolean pushable, String pageKey) {
        return dsl.insertInto(BRIEFS)
                .set(BRIEFS.SLOT_AT, slotAt)
                .set(BRIEFS.SNAPSHOT_AT, snapshotAt)
                .set(BRIEFS.SNAPSHOT, snapshot)
                .set(BRIEFS.CONTENT, content)
                .set(BRIEFS.NOTE, note)
                .set(BRIEFS.RUN_ID, runId)
                .set(BRIEFS.PUSHABLE, pushable)
                .set(BRIEFS.PAGE_KEY, pageKey)
                .onConflictDoNothing()
                .returning().fetchOptional().map(BriefRepository::toDomain);
    }

    public Optional<Brief> findById(UUID id) {
        return dsl.selectFrom(BRIEFS).where(BRIEFS.ID.eq(id)).fetchOptional().map(BriefRepository::toDomain);
    }

    /** The newest brief of any kind. */
    public Optional<Brief> latest() {
        return dsl.selectFrom(BRIEFS)
                .orderBy(BRIEFS.SNAPSHOT_AT.desc(), BRIEFS.ID.desc()).limit(1)
                .fetchOptional().map(BriefRepository::toDomain);
    }

    /** The newest brief written for a brief time; briefs that were asked for do not count. */
    public Optional<Brief> latestScheduled() {
        return dsl.selectFrom(BRIEFS).where(BRIEFS.SLOT_AT.isNotNull())
                .orderBy(BRIEFS.SNAPSHOT_AT.desc(), BRIEFS.ID.desc()).limit(1)
                .fetchOptional().map(BriefRepository::toDomain);
    }

    public boolean existsForSlot(OffsetDateTime slot) {
        return dsl.fetchExists(BRIEFS, BRIEFS.SLOT_AT.eq(slot));
    }

    /** Briefs that should reach the phone and have not, newest brief time first. */
    public List<Brief> findUndelivered() {
        return dsl.selectFrom(BRIEFS)
                .where(BRIEFS.PUSHABLE.isTrue().and(BRIEFS.DELIVERED_AT.isNull()).and(BRIEFS.SLOT_AT.isNotNull()))
                .orderBy(BRIEFS.SLOT_AT.desc()).limit(5)
                .fetch().map(BriefRepository::toDomain);
    }

    /** Records that ntfy took the push; false when it was recorded before. */
    public boolean markDelivered(UUID id, OffsetDateTime at) {
        return dsl.update(BRIEFS).set(BRIEFS.DELIVERED_AT, at)
                .where(BRIEFS.ID.eq(id).and(BRIEFS.DELIVERED_AT.isNull()))
                .execute() > 0;
    }

    /** Deletes briefs written before {@code t}. Returns how many. */
    public int deleteOlderThan(OffsetDateTime t) {
        return dsl.deleteFrom(BRIEFS).where(BRIEFS.CREATED_AT.lessThan(t)).execute();
    }

    static Brief toDomain(BriefsRecord r) {
        return new Brief(r.getId(), r.getSlotAt(), r.getSnapshotAt(), r.getSnapshot(), r.getContent(), r.getNote(),
                r.getRunId(), r.getPushable(), r.getPageKey(), r.getDeliveredAt(), r.getCreatedAt());
    }
}
