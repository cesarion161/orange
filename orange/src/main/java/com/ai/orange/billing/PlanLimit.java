package com.ai.orange.billing;

import java.util.Locale;

/**
 * Recognises Claude Code's "usage limit reached" failure on a subscription run. Such a run did not
 * fail on its merits: the plan's 5-hour or weekly allowance ran out, so it should be retried after
 * the reset rather than counted as a failed attempt.
 */
public final class PlanLimit {

    private PlanLimit() {}

    public static boolean matches(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("usage limit reached") || t.contains("limit will reset");
    }
}
