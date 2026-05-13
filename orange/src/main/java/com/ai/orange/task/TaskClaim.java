package com.ai.orange.task;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

/**
 * Live lease on a task. Issued by {@code ClaimService.claimNext} when a chat
 * (or headless) executor takes a task off the {@code ready} queue. The reaper
 * deletes rows whose {@code expiresAt} has passed and bounces the task back to
 * {@code ready} (or fails it if the per-task attempt budget is spent).
 */
public record TaskClaim(
        UUID taskId,
        UUID claimToken,
        String workerId,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        boolean cancelRequested,
        int attempt,
        JSONB metadata) {
}
