package com.ai.orange.run;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

/**
 * One agent process from queue to finish. Never carries the MCP token (only its hash lives in
 * the database, and this record does not expose it).
 *
 * @param taskId  null for plan runs
 * @param role    the routing role ({@code implementer}, {@code chore}, {@code reviewer}, …)
 * @param input   what the run was asked to address, e.g. {@code {"head_sha": …}} for a review
 * @param outcome what the agent reported through MCP; null until it does
 * @param error   the failure reason once the run failed or was cancelled
 */
public record Run(
        UUID id,
        UUID taskId,
        RunKind kind,
        RunStatus status,
        String role,
        int priority,
        int attempt,
        JSONB input,
        JSONB outcome,
        String workerId,
        OffsetDateTime leaseExpiresAt,
        Long pid,
        String tier,
        String model,
        String effort,
        String auth,
        String gatewayKeyAlias,
        BigDecimal costUsd,
        String error,
        OffsetDateTime queuedAt,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
}
