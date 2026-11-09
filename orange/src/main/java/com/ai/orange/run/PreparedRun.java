package com.ai.orange.run;

import java.nio.file.Path;

/**
 * What an agent run needs before it starts: where it works, what it is told, and what routing
 * looks at.
 *
 * @param cwd        the run's working directory (a task worktree, or the planner's output dir)
 * @param role       the routing role
 * @param difficulty S, M or L; null when unknown (routing treats it as M)
 * @param risk       whether the task is flagged risky
 * @param failures   the task's model failures so far, for escalation
 * @param cleanup    runs when the run ends, whatever happened
 */
public record PreparedRun(Path cwd, String prompt, String role, String difficulty, boolean risk, int failures,
                          Runnable cleanup) {

    public PreparedRun {
        if (cleanup == null) cleanup = () -> { };
    }
}
