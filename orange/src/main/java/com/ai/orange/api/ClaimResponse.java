package com.ai.orange.api;

import com.ai.orange.task.ClaimService.ClaimedTask;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record ClaimResponse(
        UUID taskId,
        String title,
        String role,
        String pipeline,
        int attempt,
        UUID claimToken,
        OffsetDateTime leaseExpiresAt,
        String prompt,
        String cwd,
        List<UUID> deps) {

    public static ClaimResponse of(ClaimedTask c) {
        return new ClaimResponse(
                c.task().id(),
                c.task().title(),
                c.task().role(),
                c.task().pipeline(),
                c.attempt(),
                c.claimToken(),
                c.leaseExpiresAt(),
                c.prompt(),
                c.cwd(),
                c.deps());
    }
}
