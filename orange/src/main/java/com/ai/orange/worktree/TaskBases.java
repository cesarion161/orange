package com.ai.orange.worktree;

import java.util.Optional;
import java.util.UUID;

/**
 * The base of a task that belongs to a project's repo (remote-first spec §7.1). Empty for a task
 * of no repo, whose base is the legacy {@code orange.worktrees.base-repo}.
 */
@FunctionalInterface
public interface TaskBases {

    TaskBases NONE = taskId -> Optional.empty();

    Optional<TaskBase> of(UUID taskId);
}
