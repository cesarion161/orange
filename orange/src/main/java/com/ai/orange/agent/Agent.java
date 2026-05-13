package com.ai.orange.agent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

public record Agent(
        UUID id,
        String name,
        String role,
        String systemPrompt,
        String model,
        String fallbackModel,
        JSONB allowedTools,
        JSONB disallowedTools,
        String permissionMode,
        Integer maxTurns,
        BigDecimal maxBudgetUsd,
        boolean enabled,
        JSONB metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
