package com.ai.orange.api;

import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEvent;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One in-flight task plus the last few events from its stream. Returned by
 * {@code GET /events/live} so the chat orchestrator can narrate every active
 * worker in one round-trip instead of fanning out to {@code /tasks/{id}/events}.
 */
public record LiveTaskResponse(
        UUID taskId,
        String title,
        String role,
        String pipeline,
        String status,
        String claimedBy,
        OffsetDateTime claimedAt,
        OffsetDateTime heartbeatAt,
        List<TaskEventResponse> recentEvents) {

    public static LiveTaskResponse of(Task t, List<TaskEvent> recent) {
        return new LiveTaskResponse(
                t.id(),
                t.title(),
                t.role(),
                t.pipeline(),
                t.status().dbValue(),
                t.claimedBy(),
                t.claimedAt(),
                t.heartbeatAt(),
                recent.stream().map(TaskEventResponse::of).toList());
    }
}
