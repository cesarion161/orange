package com.ai.orange.events;

import static com.ai.orange.db.jooq.Tables.EVENTS;

import com.ai.orange.db.jooq.tables.records.EventsRecord;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class EventRepository {

    /** Error text kept per failed attempt. */
    private static final int MAX_ERROR = 2_000;

    private final DSLContext dsl;

    public EventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Stores an event; false when one with this {@code deliveryId} is already stored. */
    public boolean insert(String source, String type, String repo, String deliveryId,
                          org.jooq.JSONB payload) {
        return dsl.insertInto(EVENTS)
                .set(EVENTS.SOURCE, source)
                .set(EVENTS.TYPE, type)
                .set(EVENTS.REPO, repo)
                .set(EVENTS.DELIVERY_ID, deliveryId)
                .set(EVENTS.PAYLOAD, payload)
                .onConflict(EVENTS.DELIVERY_ID)
                .doNothing()
                .execute() > 0;
    }

    /**
     * The oldest unprocessed event with attempts left, locked for the caller's transaction;
     * events another processor holds are skipped.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<InboxEvent> lockNext(int maxAttempts) {
        return dsl.selectFrom(EVENTS)
                .where(EVENTS.PROCESSED_AT.isNull().and(EVENTS.ATTEMPTS.lessThan(maxAttempts)))
                .orderBy(EVENTS.ID.asc())
                .limit(1)
                .forUpdate()
                .skipLocked()
                .fetchOptional()
                .map(EventRepository::toDomain);
    }

    /** When an event arrived and whether it has been processed. */
    public record Receipt(OffsetDateTime receivedAt, boolean processed) {}

    /** The receipt of the event stored under {@code deliveryId}; empty when none is stored. */
    public Optional<Receipt> receipt(String deliveryId) {
        return dsl.select(EVENTS.RECEIVED_AT, EVENTS.PROCESSED_AT).from(EVENTS).where(EVENTS.DELIVERY_ID.eq(deliveryId))
                .fetchOptional(r -> new Receipt(r.value1(), r.value2() != null));
    }

    /** Whether an event of this type for this session is stored but not applied yet. */
    public boolean hasUnprocessed(String source, String type, String sessionId) {
        return dsl.fetchExists(EVENTS, EVENTS.SOURCE.eq(source).and(EVENTS.TYPE.eq(type))
                .and(EVENTS.PROCESSED_AT.isNull())
                .and(org.jooq.impl.DSL.condition("{0}->>'session_id' = {1}", EVENTS.PAYLOAD,
                        org.jooq.impl.DSL.val(sessionId))));
    }

    public void markProcessed(long id) {
        dsl.update(EVENTS).set(EVENTS.PROCESSED_AT, OffsetDateTime.now()).where(EVENTS.ID.eq(id)).execute();
    }

    /** Counts a failed attempt, in its own transaction so it survives the handler's rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(long id, String error) {
        String text = error == null ? "unknown error" : error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
        dsl.update(EVENTS)
                .set(EVENTS.ATTEMPTS, EVENTS.ATTEMPTS.plus(1))
                .set(EVENTS.LAST_ERROR, text)
                .where(EVENTS.ID.eq(id))
                .execute();
    }

    static InboxEvent toDomain(EventsRecord r) {
        return new InboxEvent(r.getId(), r.getSource(), r.getType(), r.getRepo(), r.getDeliveryId(), r.getPayload(),
                r.getAttempts());
    }
}
