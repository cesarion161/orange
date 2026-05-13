package com.ai.orange.devenv;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

public record DevEnv(
        UUID id,
        String name,
        JSONB ports,
        DevEnvStatus status,
        String leasedBy,
        OffsetDateTime leasedAt,
        OffsetDateTime releasedAt,
        JSONB metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
