package com.ai.orange.run;

import java.util.Arrays;

/**
 * What a run is for (v2 spec §7, project-space spec §7). Each kind has its own slot pool,
 * timeout and executor: agent kinds run a harness, {@link #VERIFY} and {@link #INTEGRATE} are
 * carried out by orange's own code.
 */
public enum RunKind {
    /** Turns a request into a task DAG. Agent, routing role {@code planner}. */
    PLAN("plan"),
    /** Code-changing work on a task: first attempt, retry, or rework after review gaps. Agent. */
    IMPLEMENT("implement"),
    /** Code-changing fix for failed evidence or an integration conflict. Agent, role {@code ci_fixer}. */
    CI_FIX("ci_fix"),
    /** Orange runs the evidence commands itself against the task's head. Not an agent. */
    VERIFY("verify"),
    /** An LLM read of the diff against the task's intent. Agent, routing role {@code reviewer}. */
    DIFF_REVIEW("diff_review"),
    /** Rebase onto the base, then fast-forward and push it. Not an agent; one at a time. */
    INTEGRATE("integrate"),
    /** Decides what happens when a session stops (project-space phase B). Agent. */
    JUDGE("judge"),
    /** Writes the focus brief (project-space phase D). Agent. */
    BRIEF("brief");

    private final String dbValue;

    RunKind(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static RunKind fromDb(String value) {
        return Arrays.stream(values())
                .filter(k -> k.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown run kind: " + value));
    }
}
