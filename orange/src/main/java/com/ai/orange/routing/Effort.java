package com.ai.orange.routing;

import java.util.Locale;

/**
 * Reasoning effort for a run, in the order Claude Code's {@code --effort} flag accepts them.
 * Declaration order is significant: it is the escalation ladder ({@link #raise()}) and the
 * ordering used when a risk floor takes the higher of two efforts.
 */
public enum Effort {
    LOW, MEDIUM, HIGH, XHIGH, MAX;

    /** The value passed to {@code claude --effort}. */
    public String cli() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** One step up the ladder; {@code MAX} stays {@code MAX}. */
    public Effort raise() {
        Effort[] all = values();
        return this == MAX ? MAX : all[ordinal() + 1];
    }

    /** Parses the CLI spelling ({@code low} … {@code max}); anything else is rejected. */
    public static Effort parse(String value) {
        for (Effort e : values()) {
            if (e.cli().equals(value)) return e;
        }
        throw new IllegalArgumentException("unknown effort '" + value + "' (expected low|medium|high|xhigh|max)");
    }

    static Effort max(Effort a, Effort b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
