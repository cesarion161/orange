package com.ai.orange.project;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.jooq.JSONB;

/**
 * One entry in a project's space (project-space spec §5). {@code body} is {@code {"text": …}},
 * or {@code {"value": …}} for a convention. A proposal is any kind with status {@code proposed}.
 */
public record SpaceItem(UUID id, UUID projectId, UUID sessionId, UUID taskId, String kind, String key, JSONB body,
                        String status, String authorKind, String authorRef, OffsetDateTime dueAt, UUID supersedesId,
                        OffsetDateTime createdAt) {

    public static final String GOAL = "goal";
    public static final String MILESTONE = "milestone";
    public static final String CONVENTION = "convention";
    public static final String DECISION = "decision";
    public static final String NOTE = "note";
    public static final String QUESTION = "question";
    public static final Set<String> KINDS = Set.of(GOAL, MILESTONE, CONVENTION, DECISION, NOTE, QUESTION);

    public static final String PROPOSED = "proposed";
    public static final String ACTIVE = "active";
    public static final String DONE = "done";
    public static final String DROPPED = "dropped";
    public static final String SUPERSEDED = "superseded";

    public static final String USER = "user";
    public static final String ORANGE = "orange";
    public static final String AGENT = "agent";
    public static final Set<String> AUTHORS = Set.of(USER, ORANGE, AGENT);
}
