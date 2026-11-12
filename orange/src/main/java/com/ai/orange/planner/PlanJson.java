package com.ai.orange.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * Wire format the planner agent writes to {@code plan.json}, matching the
 * contract baked into the planner's system prompt. Unknown fields (such as the
 * v1 {@code pipeline}) are ignored, so older plans still parse.
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
            Map<String, Object> overrides,
            Boolean risk,
            Boolean review) {

        /** A task without the {@code risk} and {@code review} flags. */
        public PlanTask(String key, String title, String description, String role, Integer priority,
                        Map<String, Object> overrides) {
            this(key, title, description, role, priority, overrides, null, null);
        }

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
