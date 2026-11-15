package com.ai.orange.worktree;

import java.nio.file.Path;

/**
 * What a task's work comes from and goes back to: the repository its worktree is added from, the
 * branch it starts from and is integrated onto, and the {@code origin} of that branch (null: no
 * remote, the local branch is the base).
 *
 * @param repo   a repository only orange's own git runs in: the repo's mirror, or the legacy base repo
 */
public record TaskBase(Path repo, String branch, Remote remote) {

    public boolean hasOrigin() {
        return remote != null;
    }

    /** The ref the task branches from, is diffed against and is integrated onto. */
    public String ref() {
        return hasOrigin() ? "origin/" + branch : branch;
    }

    /** Brings {@link #ref()} up to date; nothing to do without a remote. */
    public void fetch() {
        if (remote != null) remote.fetch();
    }
}
