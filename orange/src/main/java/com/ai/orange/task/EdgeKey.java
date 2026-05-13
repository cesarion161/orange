package com.ai.orange.task;

/**
 * A directed edge expressed in caller-supplied keys (matching {@link TaskDef#key()}).
 * "{@code from} must finish before {@code to} can start."
 */
public record EdgeKey(String from, String to) implements DagEdge<String> {
    public EdgeKey {
        if (from == null || from.isBlank()) throw new IllegalArgumentException("from required");
        if (to == null || to.isBlank()) throw new IllegalArgumentException("to required");
        if (from.equals(to)) throw new IllegalArgumentException("self-loop edge: " + from);
    }
}
