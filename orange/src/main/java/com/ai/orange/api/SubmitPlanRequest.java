package com.ai.orange.api;

import jakarta.validation.constraints.NotNull;

public record SubmitPlanRequest(
        @NotNull java.util.UUID claimToken,
        @NotNull com.ai.orange.planner.PlanJson plan,
        String architectureMd,
        /**
         * Optional pipeline applied to any task that doesn't carry an
         * explicit one. Null falls back to the service default
         * ({@code dev_only}). Must resolve in PipelineRegistry.
         */
        String defaultPipeline) {
}
