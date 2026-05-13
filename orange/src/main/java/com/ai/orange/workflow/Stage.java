package com.ai.orange.workflow;

/**
 * One stage of a {@link Pipeline}: which agent role runs it, what to do when
 * it fails, and whether it needs a live {@code dev_env} leased for the run.
 *
 * <p>{@code requiresEnv=true} stages (typically testers) get a {@link EnvInfo}
 * leased from the {@code dev_envs} pool before the agent starts, threaded into
 * the agent prompt as a URL/port block, and released regardless of outcome.
 * The lease pool count is therefore the natural cap on tester parallelism.
 */
public record Stage(String name, String role, OnFailure onFailure, boolean requiresEnv) {

    public Stage {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (role == null || role.isBlank()) throw new IllegalArgumentException("role required");
        if (onFailure == null) onFailure = OnFailure.FAIL_TASK;
    }

    public static Stage required(String name, String role) {
        return new Stage(name, role, OnFailure.FAIL_TASK, false);
    }

    public static Stage retryPrevious(String name, String role) {
        return new Stage(name, role, OnFailure.RETRY_PREVIOUS, false);
    }

    /** Returns a copy with {@code requiresEnv=true}. */
    public Stage withEnv() {
        return new Stage(name, role, onFailure, true);
    }
}
