package com.ai.orange.run;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * How a run ended.
 *
 * @param reason            the failure reason (see {@link FailureReasons}); null on success
 * @param outcome           what the agent reported through MCP; null when it reported nothing
 * @param lastAssistantText the agent's last message, redacted
 * @param resultText        the harness's final result text, which carries e.g. a plan-limit reset time
 */
public record RunResult(UUID runId, RunStatus status, String reason, RunOutcome outcome,
                        String lastAssistantText, String resultText, BigDecimal costUsd) {

    public boolean succeeded() {
        return status == RunStatus.SUCCEEDED;
    }

    public RunResult withFailure(String failureReason) {
        return new RunResult(runId, RunStatus.FAILED, failureReason, outcome, lastAssistantText, resultText, costUsd);
    }

    static RunResult failed(UUID runId, String reason) {
        return new RunResult(runId, "cancelled".equals(reason) ? RunStatus.CANCELLED : RunStatus.FAILED, reason, null,
                null, null, BigDecimal.ZERO);
    }
}
