package com.ai.orange.harness;

/**
 * How a run's process ended.
 *
 * @param exitCode   the process exit code
 * @param killReason null unless orange killed the run: {@code timeout}, {@code budget} or {@code cancelled}
 * @param result     the last result event the harness reported, or null if it never reported one
 */
public record RunExit(int exitCode, String killReason, RunEvent.Result result) {}
