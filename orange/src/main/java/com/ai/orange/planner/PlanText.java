package com.ai.orange.planner;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The body of a {@code plan_approval} item (remote-first spec §7.2): what the user approves, drops or
 * revises. One line per task with its risk mark, the order, why orange asks and the evidence the
 * planner proposes. Pure.
 */
public final class PlanText {

    private PlanText() {
    }

    /**
     * @param reason          why orange asks, one line
     * @param evidenceInForce whether the project has evidence commands in force: then approving does not
     *                        put the proposed ones in force
     */
    public static String body(String request, String reason, PlanJson plan, boolean evidenceInForce) {
        StringBuilder b = new StringBuilder();
        b.append("Request: ").append(line(request)).append('\n');
        if (reason != null && !reason.isBlank()) b.append("Why orange asks: ").append(line(reason)).append('\n');
        List<PlanJson.PlanTask> tasks = plan.tasks() == null ? List.of() : plan.tasks();
        b.append('\n').append(tasks.size()).append(tasks.size() == 1 ? " task:\n" : " tasks:\n");
        for (PlanJson.PlanTask t : tasks) {
            b.append("- ").append(line(t.key())).append(": ").append(line(t.title()));
            if (Boolean.TRUE.equals(t.risk())) {
                String why = t.riskReason() == null || t.riskReason().isBlank() ? "flagged by the planner"
                        : line(t.riskReason());
                b.append(" (risky: ").append(why).append(')');
            }
            if (t.repo() != null && !t.repo().isBlank()) b.append(" [").append(line(t.repo())).append(']');
            b.append('\n');
        }
        List<PlanJson.PlanEdge> edges = plan.edges() == null ? List.of() : plan.edges();
        if (!edges.isEmpty()) {
            b.append("Order: ").append(edges.stream().map(e -> line(e.from()) + " → " + line(e.to()))
                    .collect(Collectors.joining(", "))).append('\n');
        }
        if (!plan.evidence().isEmpty()) {
            b.append('\n').append(evidenceInForce
                    ? "Evidence the planner proposes (the project's own stays in force):\n"
                    : "Evidence the planner proposes; approving puts it in force:\n");
            plan.evidence().forEach(c -> b.append("- ").append(line(c)).append('\n'));
        }
        b.append("\nAnswer approve, drop, or what to change.");
        return b.toString();
    }

    private static String line(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }
}
