package com.ai.orange.run;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Whether a head needs a diff review (remote-first spec §7.3): evidence commands are the gate, a
 * review is optional. The {@code review} convention says {@code never}, {@code risky} (the default:
 * a task that asks for one, or work that is risky) or {@code always}. A task's own flag adds a review
 * under {@code risky} but never overrides {@code never}, which is the user's word.
 */
public final class ReviewPolicy {

    public enum Mode {
        NEVER, RISKY, ALWAYS;

        /** {@code never}, {@code risky} or {@code always}; anything else is the default, {@code risky}. */
        public static Mode parse(JsonNode value) {
            if (value == null || !value.isTextual()) return RISKY;
            return switch (value.asText()) {
                case "never" -> NEVER;
                case "always" -> ALWAYS;
                default -> RISKY;
            };
        }
    }

    private ReviewPolicy() {}

    public static boolean required(Mode mode, boolean taskAsks, boolean risky) {
        return switch (mode) {
            case NEVER -> false;
            case RISKY -> taskAsks || risky;
            case ALWAYS -> true;
        };
    }
}
