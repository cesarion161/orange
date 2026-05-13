package com.ai.orange.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Wire format the planner agent writes to {@code plan.json}, matching the
 * contract baked into the planner's V5-seeded system prompt.
 *
 * <p>{@code pipeline} (optional) names a registered pipeline in
 * {@link com.ai.orange.workflow.PipelineRegistry} — typically {@code dev_only},
 * {@code dev_qa}, or {@code dev_review_qa}. If null/blank, falls back to the
 * planner-level default. Unknown names are rejected at submit time, so a
 * planner typo can't silently disable review.
 *
 * <p>{@code overrides} is a small closed-vocabulary bag of per-task flags the
 * orchestrator may consult (e.g. {@code skip_review}, {@code requires_auth}).
 * Stored as-is in {@code tasks.metadata.overrides}; unrecognized keys are
 * preserved but ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanJson(List<PlanTask> tasks, List<PlanEdge> edges) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanTask(
            String key,
            String title,
            String description,
            String role,
            Integer priority,
            String pipeline,
            Map<String, Object> overrides) {

        // Compact constructor only enforces non-null key; the rest is enforced
        // when we turn this into a TaskDef.
        public PlanTask {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("plan task missing key");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanEdge(String from, String to) {}
}
