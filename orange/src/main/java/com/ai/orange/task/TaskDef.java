package com.ai.orange.task;

import org.jooq.JSONB;

/**
 * Input to {@link TaskService#createGraph}: one task in a graph the caller wants
 * to insert. {@code key} is the caller's opaque identifier used to wire up edges
 * before any DB UUIDs exist (e.g. a planner emits {@code "auth-api"}).
 */
public record TaskDef(
        String key,
        String title,
        String description,
        String role,
        String pipeline,
        Integer priority,
        JSONB metadata) {

    public TaskDef {
        if (key == null || key.isBlank()) throw new IllegalArgumentException("key required");
        if (title == null || title.isBlank()) throw new IllegalArgumentException("title required");
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role required");
    }

    public int priorityOrDefault() {
        return priority == null ? 100 : priority;
    }

    public String pipelineOrDefault() {
        return pipeline == null ? "dev_only" : pipeline;
    }

    public JSONB metadataOrEmpty() {
        return metadata == null ? JSONB.valueOf("{}") : metadata;
    }
}
