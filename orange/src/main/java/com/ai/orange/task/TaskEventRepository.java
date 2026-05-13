package com.ai.orange.task;

import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;

import com.ai.orange.db.jooq.tables.records.TaskEventsRecord;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class TaskEventRepository {

    private final DSLContext dsl;

    public TaskEventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public long append(UUID taskId, String actor, String eventType, JSONB payload) {
        return dsl.insertInto(TASK_EVENTS)
                .set(TASK_EVENTS.TASK_ID, taskId)
                .set(TASK_EVENTS.ACTOR, actor)
                .set(TASK_EVENTS.EVENT_TYPE, eventType)
                .set(TASK_EVENTS.PAYLOAD, payload == null ? JSONB.valueOf("{}") : payload)
                .returning(TASK_EVENTS.ID)
                .fetchOne()
                .getId();
    }

    /**
     * Page of events for {@code taskId} with {@code id > sinceId}, oldest first.
     * Pass {@code sinceId=0} to start from the beginning. Limit is clamped to 500.
     */
    public List<TaskEvent> findByTaskSince(UUID taskId, long sinceId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 500);
        return dsl.selectFrom(TASK_EVENTS)
                .where(TASK_EVENTS.TASK_ID.eq(taskId).and(TASK_EVENTS.ID.gt(sinceId)))
                .orderBy(TASK_EVENTS.ID.asc())
                .limit(safeLimit)
                .fetch()
                .map(TaskEventRepository::toDomain);
    }

    /**
     * Most-recent {@code limit} events for {@code taskId}, ordered newest-first.
     * Used by {@code GET /events/live} so the chat shows the last few moves of
     * each in-flight agent without paging the full history. Limit clamped to 50.
     */
    public List<TaskEvent> findLatestByTask(UUID taskId, int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        return dsl.selectFrom(TASK_EVENTS)
                .where(TASK_EVENTS.TASK_ID.eq(taskId))
                .orderBy(TASK_EVENTS.ID.desc())
                .limit(safeLimit)
                .fetch()
                .map(TaskEventRepository::toDomain);
    }

    static TaskEvent toDomain(TaskEventsRecord r) {
        return new TaskEvent(
                r.getId(),
                r.getTaskId(),
                r.getActor(),
                r.getEventType(),
                r.getPayload(),
                r.getCreatedAt());
    }
}
