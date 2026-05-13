package com.ai.orange.task;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

public record Task(
        UUID id,
        String title,
        String description,
        String role,
        TaskStatus status,
        String pipeline,
        int priority,
        String claimedBy,
        OffsetDateTime claimedAt,
        OffsetDateTime heartbeatAt,
        JSONB metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
