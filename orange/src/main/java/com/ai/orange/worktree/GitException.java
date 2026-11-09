package com.ai.orange.worktree;

import java.util.List;

/** A git command failed or timed out. {@code exitCode} is -1 for a timeout. */
public class GitException extends RuntimeException {

    private final List<String> args;
    private final int exitCode;
    private final String output;

    public GitException(List<String> args, int exitCode, String output) {
        super("git " + String.join(" ", args) + (exitCode == -1 ? " timed out" : " failed (exit " + exitCode + ")")
                + (output == null || output.isBlank() ? "" : ":\n" + output.strip()));
        this.args = List.copyOf(args);
        this.exitCode = exitCode;
        this.output = output == null ? "" : output;
    }

    public List<String> args() {
        return args;
    }

    public int exitCode() {
        return exitCode;
    }

    /** What git printed (stdout and stderr). */
    public String output() {
        return output;
    }

    public boolean timedOut() {
        return exitCode == -1;
    }
}
