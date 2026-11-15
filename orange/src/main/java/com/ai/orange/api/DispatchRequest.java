package com.ai.orange.api;

import com.ai.orange.planner.PlanJson;
import java.util.UUID;

/**
 * Body for {@code POST /dispatch}. Supply <b>exactly one</b> source of the plan:
 * an inline {@code plan} (e.g. re-imported or externally produced), or a
 * {@code planId} referencing a plan a planner already saved to disk. The
 * controller enforces the xor and maps a violation to 422.
 *
 * <p>{@code architectureMd} (inline plans only) is persisted alongside the plan
 * for auditability. {@code project} (id or name) and {@code repo} ({@code owner/name}) say where
 * the tasks are done, over what the plan itself says; a project with one repo needs no repo.
 */
public record DispatchRequest(
        PlanJson plan,
        UUID planId,
        String architectureMd,
        String project,
        String repo) {
}
