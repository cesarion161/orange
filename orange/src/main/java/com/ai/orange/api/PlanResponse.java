package com.ai.orange.api;

import com.ai.orange.planner.PlannerService.PlanResult;
import java.util.List;
import java.util.UUID;

public record PlanResponse(
        UUID planId,
        String status,
        String outputDir,
        List<TaskResponse> tasks) {

    public static PlanResponse of(PlanResult r) {
        return new PlanResponse(
                r.planId(),
                r.status(),
                r.outputDir().toString(),
                r.createdTasks().stream().map(TaskResponse::of).toList());
    }
}
