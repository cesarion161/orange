package com.ai.orange.worktree;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param root       directory under which per-task worktrees live (one subdir per task UUID)
 * @param baseRepo   path to the git repo we worktree-add from. Required at runtime —
 *                   {@link WorktreeService} throws if blank. Tests that exercise the
 *                   service construct {@link WorktreeProperties} directly with a
 *                   per-test temp repo path.
 * @param baseBranch branch new worktrees are branched off. Should match
 *                   {@code orange.github.base-branch} so worktrees and PRs share a trunk.
 *                   When an {@code origin} remote exists, {@link WorktreeService#create}
 *                   fetches this branch first and branches from {@code origin/<baseBranch>}
 *                   so stale local refs never become the base.
 * @param maxCount   cap on simultaneously-existing worktrees on disk; create() refuses past this
 */
@ConfigurationProperties(prefix = "orange.worktrees")
public record WorktreeProperties(
        @DefaultValue("./.worktrees") String root,
        @DefaultValue("") String baseRepo,
        @DefaultValue("main") String baseBranch,
        @DefaultValue("20") int maxCount) {
}
