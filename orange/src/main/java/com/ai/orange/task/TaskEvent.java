package com.ai.orange.task;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

public record TaskEvent(
        long id,
        UUID taskId,
        String actor,
        String eventType,
        JSONB payload,
        OffsetDateTime createdAt) {
}
