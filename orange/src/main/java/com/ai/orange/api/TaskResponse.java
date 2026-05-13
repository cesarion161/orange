package com.ai.orange.api;

import com.ai.orange.task.Task;
import com.ai.orange.task.TaskStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TaskResponse(
        UUID id,
        String title,
        String description,
        String role,
        String pipeline,
        TaskStatus status,
        int priority,
        OffsetDateTime createdAt) {

    public static TaskResponse of(Task t) {
        return new TaskResponse(
                t.id(), t.title(), t.description(), t.role(),
                t.pipeline(), t.status(), t.priority(), t.createdAt());
    }
}
