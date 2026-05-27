package com.ai.orange.api;

import jakarta.validation.constraints.NotNull;

/**
 * Body for {@code POST /claims/{token}/submit-plan}: a chat-planner saving the
 * plan it drafted. Plan-only — pipeline selection is a dispatch-time concern
 * ({@code POST /dispatch}), so there is no {@code defaultPipeline} here.
 */
public record SubmitPlanRequest(
        @NotNull java.util.UUID claimToken,
        @NotNull com.ai.orange.planner.PlanJson plan,
        String architectureMd) {
}
