package com.ai.orange.session;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One of the user's interactive sessions in some harness, as orange sees it through its reporter. */
public record Session(UUID id, UUID projectId, String harness, String externalId, String nodeId, String cwd,
                      String repoRoot, String branch, String worktree, String intent, String status,
                      String waitingReason, OffsetDateTime waitingSince, String lastMessage, int autoContinues,
                      OffsetDateTime lastEventAt, OffsetDateTime lastUserPromptAt, String closedReason,
                      OffsetDateTime createdAt) {

    public static final String ACTIVE = "active";
    public static final String STOPPED = "stopped";
    public static final String WAITING_ON_USER = "waiting_on_user";
    public static final String ENDED = "ended";

    /** It stopped and its last message asks something. */
    public static final String WAIT_QUESTION = "question";
    /** It shows a prompt only the user can answer. */
    public static final String WAIT_PERMISSION = "permission";
    /** It stopped with nothing to ask. */
    public static final String WAIT_FINISHED = "finished";
}
