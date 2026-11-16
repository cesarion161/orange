package com.ai.orange.planner;

import java.util.List;

/**
 * What the planner is told (remote-first spec §7.2): the project's facts as orange knows them,
 * then the user's request, then the user's revision note when the plan revises an earlier one.
 * The request and the note are the user's own words and the space items are the user's or
 * accepted ones, so nothing here needs a fence. Pure, so the text is tested on its own.
 */
public final class PlannerPrompt {

    private PlannerPrompt() {
    }

    /**
     * @param repos      the project's repos, {@code owner/name}
     * @param baseBranch the branch the work integrates onto; null when unknown
     * @param evidence   the evidence commands in force; empty when none
     * @param review     the {@code review} convention in force
     * @param repoCloned whether the repository is checked out at {@code repo/} in the planner's directory
     */
    public record Facts(String project, List<String> repos, String baseBranch, List<String> goals,
                        List<Milestone> milestones, List<String> decisions, List<String> notes,
                        List<String> evidence, String review, boolean repoCloned) {

        public Facts {
            repos = copy(repos);
            goals = copy(goals);
            milestones = milestones == null ? List.of() : List.copyOf(milestones);
            decisions = copy(decisions);
            notes = copy(notes);
            evidence = copy(evidence);
        }
    }

    /** A milestone the plan may serve: its id is what the planner names in {@code milestone}. */
    public record Milestone(String id, String text, String due) {
    }

    public static String build(String request, String note, Facts facts) {
        StringBuilder p = new StringBuilder();
        p.append("# The project: ").append(facts.project()).append("\n\n");
        p.append("- repos: ").append(facts.repos().isEmpty() ? "none" : String.join(", ", facts.repos())).append('\n');
        if (facts.baseBranch() != null && !facts.baseBranch().isBlank()) {
            p.append("- base branch: ").append(facts.baseBranch()).append('\n');
        }
        p.append("- evidence in force: ");
        if (facts.evidence().isEmpty()) p.append("none\n");
        else {
            p.append('\n');
            facts.evidence().forEach(c -> p.append("  - `").append(c).append("`\n"));
        }
        if (facts.review() != null && !facts.review().isBlank()) {
            p.append("- review: ").append(facts.review()).append('\n');
        }
        p.append('\n');
        list(p, "goals", facts.goals());
        p.append("## milestones");
        if (facts.milestones().isEmpty()) p.append(": none\n\n");
        else {
            p.append("\n\n");
            for (Milestone m : facts.milestones()) {
                p.append("- ").append(m.text()).append(" (id `").append(m.id()).append('`');
                if (m.due() != null && !m.due().isBlank()) p.append(", due ").append(m.due());
                p.append(")\n");
            }
            p.append('\n');
        }
        list(p, "decisions", facts.decisions());
        list(p, "notes", facts.notes());
        if (facts.repoCloned()) {
            p.append("The repository is checked out read-only at repo/ in your working directory. Read it before "
                    + "you plan; never write inside it.\n\n");
        }
        p.append("# The request\n\n").append(request.strip()).append('\n');
        if (note != null && !note.isBlank()) {
            p.append("\n# The user asked for this revision of an earlier plan:\n\n").append(note.strip()).append('\n');
        }
        return p.toString();
    }

    private static void list(StringBuilder p, String heading, List<String> items) {
        p.append("## ").append(heading);
        if (items.isEmpty()) {
            p.append(": none\n\n");
            return;
        }
        p.append("\n\n");
        items.forEach(i -> p.append("- ").append(i).append('\n'));
        p.append('\n');
    }

    private static List<String> copy(List<String> l) {
        return l == null ? List.of() : l.stream().filter(s -> s != null && !s.isBlank()).map(String::strip).toList();
    }
}
