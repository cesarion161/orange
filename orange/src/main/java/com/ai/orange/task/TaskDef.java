package com.ai.orange.task;

import com.ai.orange.project.TaskPlacement;
import java.util.UUID;
import org.jooq.JSONB;

/**
 * Input to {@link TaskService#createGraph}: one task in a graph the caller wants
 * to insert. {@code key} is the caller's opaque identifier used to wire up edges
 * before any DB UUIDs exist (e.g. a planner emits {@code "auth-api"}).
 *
 * <p>{@code role} is normalised to {@code implementer} or {@code chore}: v1 roles
 * ({@code dev}, {@code tester}, {@code reviewer}, {@code triage}) and blanks become
 * {@code implementer}, matching what V14 did to existing rows.
 *
 * <p>{@code placement} is the project and repo the task's work is done in; {@link TaskPlacement#NONE}
 * (or null) is the legacy base repo. {@code planId} and {@code milestoneId} are the plan that made the
 * task and the milestone it serves; both null for a task created by hand.
 */
public record TaskDef(
        String key,
        String title,
        String description,
        String role,
        Integer priority,
        JSONB metadata,
        TaskPlacement placement,
        UUID planId,
        UUID milestoneId) {

    public static final String IMPLEMENTER = "implementer";
    public static final String CHORE = "chore";

    public TaskDef {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        role = normalizeRole(role);
        if (placement == null) placement = TaskPlacement.NONE;
    }

    /** A task of no plan. */
    public TaskDef(String key, String title, String description, String role, Integer priority, JSONB metadata,
                   TaskPlacement placement) {
        this(key, title, description, role, priority, metadata, placement, null, null);
    }

    /** A task of no project: the legacy base repo. */
    public TaskDef(String key, String title, String description, String role, Integer priority, JSONB metadata) {
        this(key, title, description, role, priority, metadata, TaskPlacement.NONE);
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
