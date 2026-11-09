package com.ai.orange.run;

import java.util.Set;

/**
 * Carries out claimed runs of some kinds: the agent runner for the agent kinds, orange's own code
 * for verification and integration. {@code execute} takes a run already claimed as running and
 * returns once the run row is finished.
 */
public interface RunExecutor {

    Set<RunKind> kinds();

    RunResult execute(Run run);
}
