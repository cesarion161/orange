package com.ai.orange.project;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** A named area of work (project-space spec §5). */
public record Project(UUID id, String name, String status, String summary, OffsetDateTime createdAt) {

    /** orange saw a repo it did not know; the user has not confirmed or ignored it yet. */
    public static final String PROPOSED = "proposed";
    public static final String ACTIVE = "active";
    public static final String PAUSED = "paused";
    /** Ignored: its sessions raise nothing and the brief skips it. */
    public static final String ARCHIVED = "archived";
    public static final Set<String> STATUSES = Set.of(PROPOSED, ACTIVE, PAUSED, ARCHIVED);

    public boolean archived() {
        return ARCHIVED.equals(status);
    }
}
