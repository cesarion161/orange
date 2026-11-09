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
        TaskStatus status,
        int priority,
        int failures,
        String repo,
        String branch,
        Integer prNumber,
        String prUrl,
        String headSha,
        String reviewedSha,
        String reviewVerdict,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static TaskResponse of(Task t) {
        return new TaskResponse(
                t.id(), t.title(), t.description(), t.role(), t.status(), t.priority(), t.failures(),
                t.repo(), t.branch(), t.prNumber(), t.prUrl(), t.headSha(), t.reviewedSha(),
                t.reviewVerdict(), t.createdAt(), t.updatedAt());
    }
}
