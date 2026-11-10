package com.ai.orange.session;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One piece of work in a session: an intent, from the prompt that stated it to its close
 * (project-space spec decision 10). orange closes work, never the session itself.
 *
 * @param result how it closed: {@code integrated} or {@code verified}; null while open
 * @param note   what orange did at the close and what it left alone
 */
public record SessionWork(UUID id, UUID sessionId, String intent, String status, String result, String headSha,
                          String branch, String baseBranch, String note, OffsetDateTime openedAt,
                          OffsetDateTime closedAt) {

    public static final String OPEN = "open";
    public static final String CLOSED = "closed";

    public static final String INTEGRATED = "integrated";
    public static final String VERIFIED = "verified";
}
