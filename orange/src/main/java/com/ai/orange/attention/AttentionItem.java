package com.ai.orange.attention;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** Something that wants the user (project-space spec §5). */
public record AttentionItem(UUID id, UUID projectId, UUID sessionId, UUID taskId, String kind, String dedupeKey,
                            String urgency, String title, String body, String proposedAnswer, String status,
                            OffsetDateTime pushedAt, String resolvedBy, String resolution, OffsetDateTime resolvedAt,
                            OffsetDateTime createdAt) {

    public static final String CRITICAL = "critical";
    public static final String URGENT = "urgent";
    public static final String NORMAL = "normal";
    public static final Set<String> URGENCIES = Set.of(CRITICAL, URGENT, NORMAL);

    public static final String OPEN = "open";
    public static final String ACKED = "acked";
    public static final String RESOLVED = "resolved";

    /** A session stopped with a question in its last message. */
    public static final String SESSION_QUESTION = "session_question";
    /** A session shows a prompt only the user can answer (a permission, an input dialog). */
    public static final String SESSION_WAITING = "session_waiting";
    /** A session stopped with nothing to ask and nobody came back to it. */
    public static final String SESSION_IDLE = "session_idle";
    /** orange answered a session several times in a row and it stopped again: the loop guard. */
    public static final String SESSION_LOOP = "session_loop";
    /** orange saw a repo it does not know and proposes a project for it. */
    public static final String PROJECT_PROPOSED = "project_proposed";

    public boolean critical() {
        return CRITICAL.equals(urgency);
    }
}
