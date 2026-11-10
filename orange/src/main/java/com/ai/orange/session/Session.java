package com.ai.orange.session;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One of the user's interactive sessions in some harness, as orange sees it through its reporter. */
public record Session(UUID id, UUID projectId, String harness, String externalId, String nodeId, String cwd,
                      String repoRoot, String branch, String worktree, String intent, String status,
                      String waitingReason, OffsetDateTime waitingSince, String lastMessage, int autoContinues,
                      OffsetDateTime lastEventAt, OffsetDateTime lastUserPromptAt, String closedReason,
                      OffsetDateTime createdAt, String stopEventId) {

    /** orange is deciding what happens at this stop; the stop hook is waiting for the verdict. */
    public static final String JUDGING = "judging";


    /** The judge said it is finished; orange is checking, and then integrating, the work. */
    public static final String VERIFYING = "verifying";
    /** Its work was verified and closed. The session itself lives on: a new prompt opens new work. */
    public static final String CLOSED = "closed";

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

    /** This session in another state of waiting ({@code reason} and {@code since} null = not waiting). */
    public Session waiting(String newStatus, String reason, OffsetDateTime since) {
        return new Session(id, projectId, harness, externalId, nodeId, cwd, repoRoot, branch, worktree, intent,
                newStatus, reason, since, lastMessage, autoContinues, lastEventAt, lastUserPromptAt, closedReason,
                createdAt, stopEventId);
    }

    /** This session with its work closed: not waiting for anyone, and why it closed. */
    public Session closed(String reason) {
        return new Session(id, projectId, harness, externalId, nodeId, cwd, repoRoot, branch, worktree, intent,
                CLOSED, null, null, lastMessage, 0, lastEventAt, lastUserPromptAt, reason, createdAt, stopEventId);
    }

    public Session withAutoContinues(int n) {
        return new Session(id, projectId, harness, externalId, nodeId, cwd, repoRoot, branch, worktree, intent,
                status, waitingReason, waitingSince, lastMessage, n, lastEventAt, lastUserPromptAt, closedReason,
                createdAt, stopEventId);
    }
}
