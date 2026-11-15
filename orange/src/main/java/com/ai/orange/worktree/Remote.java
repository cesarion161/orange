package com.ai.orange.worktree;

import java.nio.file.Path;

/**
 * The {@code origin} a base repo integrates with: how orange fetches from it and pushes to it. A
 * mirror's remote authenticates with the owner's token and lets one git command at a time talk to
 * it; the legacy base repo's uses the host's credentials.
 *
 * <p>Both methods throw {@link GitException} when git fails.
 */
public interface Remote {

    /** Brings every {@code origin/*} ref up to date. */
    void fetch();

    /** {@code git push origin <refspecs>}, run in {@code dir}: the repo or one of its worktrees. */
    void push(Path dir, String... refspecs);
}
