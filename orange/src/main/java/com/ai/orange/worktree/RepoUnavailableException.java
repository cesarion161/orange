package com.ai.orange.worktree;

/**
 * A task's base cannot be had: its repo's mirror cannot be cloned or fetched (a missing or revoked
 * token, a deleted repo, GitHub down), its base branch is not there, or a task of no repo has no
 * base repo configured. It is that task's trouble, not orange's: its run fails
 * {@code repo_unavailable}, which counts against the task and does not pause the scheduler.
 */
public class RepoUnavailableException extends RuntimeException {

    public RepoUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
