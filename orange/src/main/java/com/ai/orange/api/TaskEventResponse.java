package com.ai.orange.api;

import com.ai.orange.task.TaskEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskEventResponse(
        long id,
        UUID taskId,
        String actor,
        String eventType,
        Object payload,
        OffsetDateTime createdAt) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static TaskEventResponse of(TaskEvent e) {
        Object payload = null;
        if (e.payload() != null) {
            try {
                payload = MAPPER.readValue(e.payload().data(), Object.class);
            } catch (Exception ex) {
                payload = null;
            }
        }
        return new TaskEventResponse(e.id(), e.taskId(), e.actor(), e.eventType(), payload, e.createdAt());
    }
}
