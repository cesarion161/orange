package com.ai.orange.status;

import com.ai.orange.brief.BriefSnapshot.Milestone;
import com.ai.orange.brief.MilestoneTracks;
import com.ai.orange.brief.OnTrack;
import com.ai.orange.planner.Plan;
import com.ai.orange.status.StatusReport.PlanLine;
import com.ai.orange.status.StatusReport.ProjectStatus;
import com.ai.orange.status.StatusReport.TaskLine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@link StatusReport} in words: the full text of {@code orange_status}, and the few lines the steering
 * tab starts with ({@link #startContext}). Every name and title is flattened to one line, so a stored
 * value never adds a line of its own.
 */
public final class StatusText {

    /** The start context never has more lines than this. */
    public static final int MAX_CONTEXT_LINES = 8;
    static final String HINT = "Ask \"what's the status\" or hand over work: \"build X in <project>\".";
    private static final int NAME = 60;
    private static final int MILESTONE = 40;

    private StatusText() {}

    /**
     * At most {@link #MAX_CONTEXT_LINES} lines: the pause if there is one, a line per active project (those
     * that want the user first; the rest counted on one line), the tasks of no project, what waits for the
     * user, and the hint.
     */
    public static String startContext(StatusReport r) {
        List<String> head = new ArrayList<>();
        if (r.pause() != null) head.add(pauseLine(r));
        List<String> body = new ArrayList<>();
        for (ProjectStatus p : r.projects()) body.add(cut(p.name(), NAME) + ": " + projectWords(p));
        if (!r.unplaced().isEmpty()) body.add("no project: " + unplacedWords(r.unplaced()));
        if (r.projects().isEmpty()) {
            body.addFirst("No active project yet: say \"start a project <name> on <owner/name>\".");
        }
        List<String> tail = List.of(waitingLine(r), HINT);

        int room = MAX_CONTEXT_LINES - head.size() - tail.size();
        if (body.size() > room) {
            int shown = room - 1;
            int rest = body.size() - shown;
            body = new ArrayList<>(body.subList(0, shown));
            body.add("…and " + rest + " more; orange_status shows them all.");
        }
        List<String> lines = new ArrayList<>(head);
        lines.addAll(body);
        lines.addAll(tail);
        return String.join("\n", lines);
    }

    /** Everything in the report, for {@code orange_status}. */
    public static String text(StatusReport r) {
        StringBuilder out = new StringBuilder();
        out.append("orange status at ").append(r.at()).append('\n');
        out.append(r.pause() == null ? "Scheduler: running." : pauseLine(r)).append('\n');
        out.append("Runs running: ").append(r.runningRuns()).append(". ").append(waitingLine(r)).append('\n');
        if (r.projects().isEmpty()) out.append("\nNo active project.\n");
        for (ProjectStatus p : r.projects()) {
            out.append('\n').append(one(p.name())).append(" (").append(p.id()).append(")\n");
            for (PlanLine plan : p.plans()) {
                out.append("  plan ").append(plan.id()).append(' ')
                        .append(Plan.ASKING.equals(plan.status()) ? "asks you first" : "is being planned")
                        .append(": \"").append(one(plan.request())).append('"');
                if (plan.reason() != null && !plan.reason().isBlank()) out.append(" (").append(one(plan.reason())).append(')');
                out.append('\n');
            }
            out.append("  tasks: ").append(p.tasksByState().isEmpty() ? "none in flight" : counts(p.tasksByState()));
            if (p.integratedToday() > 0) out.append("; ").append(p.integratedToday()).append(" integrated today");
            out.append('\n');
            taskLines(out, p.tasks());
            for (Milestone m : p.milestones()) out.append("  ").append(milestoneLine(m)).append('\n');
            if (p.openAttention() > 0) {
                out.append("  waiting for you: ").append(p.openAttention()).append(p.openAttention() == 1 ? " item" : " items")
                        .append('\n');
            }
        }
        if (!r.unplaced().isEmpty()) {
            out.append("\nNo project (the legacy base repo)\n");
            taskLines(out, r.unplaced());
        }
        return out.toString().stripTrailing();
    }

    private static void taskLines(StringBuilder out, List<TaskLine> tasks) {
        for (TaskLine t : tasks) {
            out.append("    ").append(t.id8()).append("  ").append(t.status()).append("  ").append(one(t.title()));
            if (t.milestone() != null) out.append("  [milestone ").append(one(t.milestone())).append(']');
            out.append('\n');
        }
    }

    private static String pauseLine(StatusReport r) {
        String until = r.pause().until() == null ? "until resumed (orange_resume)" : "until " + r.pause().until();
        return "orange is paused (" + cut(r.pause().reason(), NAME) + "), " + until + ": nothing new starts.";
    }

    private static String waitingLine(StatusReport r) {
        if (r.openAttention() == 0) return "Nothing waits for you.";
        return "Waiting for you: " + r.openAttention() + (r.openAttention() == 1 ? " item" : " items")
                + (r.urgentAttention() > 0 ? ", " + r.urgentAttention() + " urgent" : "")
                + "; orange_attention lists them.";
    }

    /** {@code 2 running, 1 queued, 1 needs you, 1 being planned, milestone "beta" behind}. */
    private static String projectWords(ProjectStatus p) {
        List<String> parts = new ArrayList<>();
        int running = n(p.tasksByState(), "running") + n(p.tasksByState(), "verifying") + n(p.tasksByState(), "verified");
        int queued = n(p.tasksByState(), "ready") + n(p.tasksByState(), "pending");
        int planning = (int) p.plans().stream().filter(x -> Plan.PLANNING.equals(x.status())).count();
        if (running > 0) parts.add(running + " running");
        if (queued > 0) parts.add(queued + " queued");
        if (p.openAttention() > 0) parts.add(p.openAttention() + " needs you");
        else if (n(p.tasksByState(), "needs_human") > 0) parts.add(n(p.tasksByState(), "needs_human") + " stuck");
        if (planning > 0) parts.add(planning + " being planned");
        if (p.integratedToday() > 0) parts.add(p.integratedToday() + " integrated today");
        Milestone labelled = p.milestones().stream().filter(m -> OnTrack.BEHIND.equals(m.track())).findFirst()
                .orElseGet(() -> p.milestones().stream().filter(m -> m.track() != null).findFirst().orElse(null));
        if (labelled != null) parts.add("milestone \"" + cut(labelled.text(), MILESTONE) + "\" " + track(labelled.track()));
        return parts.isEmpty() ? "nothing in flight" : String.join(", ", parts);
    }

    private static String unplacedWords(List<TaskLine> tasks) {
        long stuck = tasks.stream().filter(t -> "needs_human".equals(t.status())).count();
        long other = tasks.size() - stuck;
        List<String> parts = new ArrayList<>();
        if (other > 0) parts.add(other + " in flight");
        if (stuck > 0) parts.add(stuck + " stuck");
        return String.join(", ", parts);
    }

    /** {@code milestone "Beta" due in 5 days (50% of its time gone): 1 merged, 1 stuck; behind}. */
    static String milestoneLine(Milestone m) {
        StringBuilder s = new StringBuilder("milestone \"").append(one(m.text())).append('"');
        if (m.daysLeft() != null) {
            int d = m.daysLeft();
            s.append(m.overdue() ? " overdue" + (d < 0 ? " by " + days(-d) : "")
                    : d == 0 ? " due today" : " due in " + days(d));
            if (m.elapsedPercent() != null) s.append(" (").append(m.elapsedPercent()).append("% of its time gone)");
        }
        s.append(": ");
        if (m.tasks().isEmpty()) {
            s.append("no tasks");
        } else {
            List<String> parts = new ArrayList<>();
            for (String state : MilestoneTracks.STATES) {
                Integer k = m.tasks().get(state);
                if (k != null && k > 0) parts.add(k + " " + state.replace('_', ' '));
            }
            s.append(String.join(", ", parts));
        }
        if (m.track() != null) s.append("; ").append(track(m.track()));
        return s.toString();
    }

    private static String track(String track) {
        return OnTrack.BEHIND.equals(track) ? "behind" : "on track";
    }

    private static String counts(Map<String, Integer> byStatus) {
        List<String> parts = new ArrayList<>();
        byStatus.forEach((k, v) -> parts.add(v + " " + k));
        return String.join(", ", parts);
    }

    private static String days(int d) {
        return d == 1 ? "1 day" : d + " days";
    }

    private static int n(Map<String, Integer> m, String key) {
        return m.getOrDefault(key, 0);
    }

    private static String one(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    private static String cut(String s, int max) {
        String flat = one(s);
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }
}
