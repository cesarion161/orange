package com.ai.orange.api;

import com.ai.orange.planner.PlanJson;
import com.ai.orange.planner.PlannerService.PlanResult;
import java.util.List;
import java.util.UUID;

/**
 * Result of {@code POST /plans} (and the chat-planner save): the reviewable
 * plan DAG plus where its artifacts live on disk. Carries <b>no created
 * tasks</b> — the planner produces a plan, the dispatcher ({@code POST
 * /dispatch}) runs it.
 */
public record PlanResponse(
        UUID planId,
        String status,
        String outputDir,
        String planJsonPath,
        String architectureMdPath,
        PlanGraph plan) {

    /** The DAG itself, mirrored from {@link PlanJson} for review/re-import. */
    public record PlanGraph(List<PlanTaskView> tasks, List<PlanEdgeView> edges) {}

    public record PlanTaskView(
            String key,
            String title,
            String description,
            String role,
            String pipeline,
            Integer priority) {}

    public record PlanEdgeView(String from, String to) {}

    public static PlanResponse of(PlanResult r) {
        return new PlanResponse(
                r.planId(),
                r.status(),
                r.outputDir() == null ? null : r.outputDir().toString(),
                r.planJson() == null ? null : r.planJson().toString(),
                r.architectureMd() == null ? null : r.architectureMd().toString(),
                toGraph(r.plan()));
    }

    private static PlanGraph toGraph(PlanJson plan) {
        if (plan == null) return null;
        List<PlanTaskView> tasks = plan.tasks() == null ? List.of()
                : plan.tasks().stream()
                        .map(t -> new PlanTaskView(
                                t.key(), t.title(), t.description(), t.role(), t.pipeline(), t.priority()))
                        .toList();
        List<PlanEdgeView> edges = plan.edges() == null ? List.of()
                : plan.edges().stream()
                        .map(e -> new PlanEdgeView(e.from(), e.to()))
                        .toList();
        return new PlanGraph(tasks, edges);
    }
}
