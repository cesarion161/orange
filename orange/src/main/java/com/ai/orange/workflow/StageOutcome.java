package com.ai.orange.workflow;

/**
 * What a single {@link Stage} produced.
 *
 * @param agentStatus  what the agent-runner reported as its final event
 *                     ({@code success} | {@code failure} | {@code cancelled}).
 * @param verdict      for tester-role stages, parsed from {@code report.md}'s
 *                     first line: {@code "pass"} or {@code "fail"}; {@code null}
 *                     for non-tester stages.
 * @param report       full text of the tester's {@code report.md}, if any.
 *                     Passed to the next attempt of the previous stage when
 *                     {@link OnFailure#RETRY_PREVIOUS} kicks in.
 */
public record StageOutcome(String agentStatus, String verdict, String report) {

    /** True iff the stage's agent reported success and (for testers) passed. */
    public boolean succeeded() {
        if (!"success".equals(agentStatus)) return false;
        if (verdict == null) return true;
        return "pass".equals(verdict);
    }
}
