package com.ai.orange.worktree;

/**
 * Every worktree slot ({@code orange.worktrees.max-count}) is taken. Not a broken workspace: the
 * task waits until the disposer frees a slot.
 */
public class WorktreeCapacityException extends IllegalStateException {

    public WorktreeCapacityException(String message) {
        super(message);
    }
}
