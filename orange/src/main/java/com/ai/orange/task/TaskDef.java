package com.ai.orange.task;

import org.jooq.JSONB;

/**
 * Input to {@link TaskService#createGraph}: one task in a graph the caller wants
 * to insert. {@code key} is the caller's opaque identifier used to wire up edges
 * before any DB UUIDs exist (e.g. a planner emits {@code "auth-api"}).
 *
 * <p>{@code role} is normalised to {@code implementer} or {@code chore}: v1 roles
 * ({@code dev}, {@code tester}, {@code reviewer}, {@code triage}) and blanks become
 * {@code implementer}, matching what V14 did to existing rows.
 */
public record TaskDef(
        String key,
        String title,
        String description,
        String role,
        Integer priority,
        JSONB metadata) {

    public static final String IMPLEMENTER = "implementer";
    public static final String CHORE = "chore";

    public TaskDef {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        role = normalizeRole(role);
    }

    public static String normalizeRole(String requested) {
        return CHORE.equals(requested == null ? null : requested.trim()) ? CHORE : IMPLEMENTER;
    }

    public int priorityOrDefault() {
        return priority == null ? 100 : priority;
    }

    public JSONB metadataOrEmpty() {
        return metadata == null ? JSONB.valueOf("{}") : metadata;
    }
}
