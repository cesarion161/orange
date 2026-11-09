package com.ai.orange.worktree;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param root       directory under which per-task worktrees live (one subdir per task UUID).
 *                   Defaults to {@code ~/.orange/worktrees}, outside every repository (v2
 *                   decision 15), so agents never find worktrees inside the repo they work on.
 * @param baseRepo   path to the git repo we worktree-add from. Required at runtime —
 *                   {@link WorktreeService} throws if blank. Tests that exercise the
 *                   service construct {@link WorktreeProperties} directly with a
 *                   per-test temp repo path.
 * @param baseBranch branch new worktrees are branched off and verified work is integrated
 *                   onto. When an {@code origin} remote exists, {@link WorktreeService#create}
 *                   fetches this branch first and branches from {@code origin/<baseBranch>}
 *                   so stale local refs never become the base.
 * @param maxCount   cap on simultaneously-existing worktrees on disk; create() refuses past this
 */
@ConfigurationProperties(prefix = "orange.worktrees")
public record WorktreeProperties(
        String root,
        @DefaultValue("") String baseRepo,
        @DefaultValue("main") String baseBranch,
        @DefaultValue("20") int maxCount) {

    public WorktreeProperties {
        if (root == null || root.isBlank()) {
            root = Path.of(System.getProperty("user.home"), ".orange", "worktrees").toString();
        }
    }
}
