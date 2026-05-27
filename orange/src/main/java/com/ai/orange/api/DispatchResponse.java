package com.ai.orange.api;

import com.ai.orange.dispatch.DispatchService.DispatchResult;
import java.util.List;
import java.util.UUID;

/** Result of {@code POST /dispatch}: the planId it ran and the tasks it created. */
public record DispatchResponse(
        UUID planId,
        String status,
        List<TaskResponse> tasks) {

    public static DispatchResponse of(DispatchResult r) {
        return new DispatchResponse(
                r.planId(),
                r.status(),
                r.createdTasks().stream().map(TaskResponse::of).toList());
    }
}
