package com.ai.orange.planner;

import java.util.Locale;

/**
 * Whether a plan dispatches at once or asks the user first (remote-first spec §7.2). The planner
 * gives its judgement ({@code ask_first}, risky tasks); the {@code plan-approval} convention
 * overrides it either way, and orange's own rules come first: without evidence in force nothing
 * could be integrated, and approving the plan is how the user puts its proposed commands in force.
 * Pure, so the rule table is tested on its own.
 */
public final class PlanApproval {

    public static final String AUTO = "auto";
    public static final String ALWAYS = "always";
    public static final String NEVER = "never";

    private PlanApproval() {
    }

    /**
     * @param ask    whether to hold the plan for the user's answer
     * @param reason one line for the user; never null when {@code ask}
     */
    public record Decision(boolean ask, String reason) {

        static final Decision DISPATCH = new Decision(false, null);

        static Decision ask(String reason) {
            return new Decision(true, reason);
        }
    }

    /**
     * The first rule that matches wins: no evidence in force asks; {@code always} asks; {@code never}
     * dispatches; otherwise (auto, blank or unknown) the planner's {@code ask_first}, a plan larger than
     * {@code askAboveTasks} and a risky task each ask, and anything else dispatches.
     */
    public static Decision decide(String convention, boolean evidenceInForce, PlanJson plan, int askAboveTasks) {
        if (!evidenceInForce) return Decision.ask("no tests in force yet");
        String c = convention == null ? AUTO : convention.strip().toLowerCase(Locale.ROOT);
        if (ALWAYS.equals(c)) return Decision.ask("plan-approval is always");
        if (NEVER.equals(c)) return Decision.DISPATCH;
        if (plan.askFirst()) return Decision.ask(blank(plan.reason()) ? "the planner asks first" : line(plan.reason()));
        int n = plan.tasks() == null ? 0 : plan.tasks().size();
        if (n > askAboveTasks) return Decision.ask(n + " tasks (more than " + askAboveTasks + ")");
        if (plan.tasks() != null) {
            for (PlanJson.PlanTask t : plan.tasks()) {
                if (Boolean.TRUE.equals(t.risk())) {
                    String why = blank(t.riskReason()) ? "flagged by the planner" : line(t.riskReason());
                    return Decision.ask(line(t.title()) + " is risky: " + why);
                }
            }
        }
        return Decision.DISPATCH;
    }

    /** The planner's text as one line: the reason is shown to the user in a title. */
    private static String line(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
