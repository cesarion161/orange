package com.ai.orange.api;

import com.ai.orange.planner.PlanJson;
import java.util.UUID;

/**
 * Body for {@code POST /dispatch}. Supply <b>exactly one</b> source of the plan:
 * an inline {@code plan} (e.g. re-imported or externally produced), or a
 * {@code planId} referencing a plan a planner already saved to disk. The
 * controller enforces the xor and maps a violation to 422.
 *
 * <p>{@code defaultPipeline} applies to any task without an explicit pipeline;
 * {@code architectureMd} (inline plans only) is persisted alongside the plan
 * for auditability.
 */
public record DispatchRequest(
        PlanJson plan,
        UUID planId,
        String architectureMd,
        String defaultPipeline) {
}
