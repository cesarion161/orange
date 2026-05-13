package com.ai.orange.api;

import com.ai.orange.task.TaskClaim;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ActiveClaimResponse(
        UUID taskId,
        UUID claimToken,
        String workerId,
        int attempt,
        OffsetDateTime issuedAt,
        OffsetDateTime leaseExpiresAt,
        boolean cancelRequested) {

    public static ActiveClaimResponse of(TaskClaim c) {
        return new ActiveClaimResponse(
                c.taskId(),
                c.claimToken(),
                c.workerId(),
                c.attempt(),
                c.issuedAt(),
                c.expiresAt(),
                c.cancelRequested());
    }
}
