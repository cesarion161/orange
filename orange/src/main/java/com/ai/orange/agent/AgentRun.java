package com.ai.orange.agent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

public record AgentRun(
        UUID id,
        UUID taskId,
        UUID agentId,
        int attempt,
        String prompt,
        AgentRunStatus status,
        BigDecimal costUsd,
        long tokensIn,
        long tokensOut,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        JSONB metadata) {
}
