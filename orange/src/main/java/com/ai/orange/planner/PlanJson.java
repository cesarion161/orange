package com.ai.orange.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 *
 * <p>{@code ask_first} and {@code reason} are the planner's judgement on whether the user should see
 * the plan before it dispatches (remote-first spec §7.2); {@link PlanApproval} has the last word.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanJson(List<PlanTask> tasks, List<PlanEdge> edges, String project, String repo,
                       @JsonProperty("ask_first") boolean askFirst, String reason) {

    /** A plan without the planner's judgement on asking first. */
    public PlanJson(List<PlanTask> tasks, List<PlanEdge> edges, String project, String repo) {
        this(tasks, edges, project, repo, false, null);
    }

    /**
     * A plan of no project (the legacy base repo). {@code project} names one (id or name) and
     * {@code repo} one of its repos ({@code owner/name}); a project with one repo needs no repo.
     */
    public PlanJson(List<PlanTask> tasks, List<PlanEdge> edges) {
        this(tasks, edges, null, null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanTask(
            String key,
            String title,
            String description,
            String role,
            Integer priority,
            Map<String, Object> overrides,
            Boolean risk,
            Boolean review,
            String repo,
            @JsonProperty("risk_reason") String riskReason) {

        /** A task without the {@code risk} and {@code review} flags. */
        public PlanTask(String key, String title, String description, String role, Integer priority,
                        Map<String, Object> overrides) {
            this(key, title, description, role, priority, overrides, null, null, null, null);
        }

        /** A task in the plan's own repo. */
        public PlanTask(String key, String title, String description, String role, Integer priority,
                        Map<String, Object> overrides, Boolean risk, Boolean review) {
            this(key, title, description, role, priority, overrides, risk, review, null, null);
        }

        /** A task without a reason for its {@code risk}. */
        public PlanTask(String key, String title, String description, String role, Integer priority,
                        Map<String, Object> overrides, Boolean risk, Boolean review, String repo) {
            this(key, title, description, role, priority, overrides, risk, review, repo, null);
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
