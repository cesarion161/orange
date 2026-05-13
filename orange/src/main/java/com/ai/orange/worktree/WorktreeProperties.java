package com.ai.orange.worktree;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param root     directory under which per-task worktrees live (one subdir per task UUID)
 * @param baseRepo path to the git repo we worktree-add from. Empty means "set per-call"
 *                 (used in tests; production should set this to the target project's repo)
 * @param maxCount cap on simultaneously-existing worktrees on disk; create() refuses past this
 */
@ConfigurationProperties(prefix = "orange.worktrees")
public record WorktreeProperties(
        @DefaultValue("./.worktrees") String root,
        @DefaultValue("") String baseRepo,
        @DefaultValue("20") int maxCount) {
}
