package com.ai.orange.api;

import com.ai.orange.task.ClaimService.HeartbeatResult;
import java.time.OffsetDateTime;
import java.util.UUID;

public record HeartbeatResponse(boolean alive, UUID taskId, boolean cancelRequested,
                                OffsetDateTime leaseExpiresAt) {

    public static HeartbeatResponse of(HeartbeatResult r) {
        return new HeartbeatResponse(r.alive(), r.taskId(), r.cancelRequested(), r.leaseExpiresAt());
    }
}
