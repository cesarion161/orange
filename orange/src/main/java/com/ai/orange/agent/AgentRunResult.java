package com.ai.orange.agent;

import com.ai.orange.harness.RunEvent;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * How an agent run ended.
 *
 * @param status            {@code success}, {@code failure} or {@code cancelled}
 * @param failureReason     null on success; otherwise one of {@code subscription_token_missing},
 *                          {@code gateway_unavailable}, {@code start_failed}, {@code timeout}, {@code budget},
 *                          {@code cancelled}, {@code plan_limit}, {@code error_result}, {@code no_result},
 *                          {@code exit_<code>}
 * @param lastAssistantText the model's last message (a reviewer's verdict lives here)
 * @param result            the harness's final result event, or null
 * @param costUsd           gateway spend for gateway runs, the harness-reported or estimated cost otherwise
 * @param agentRunId        the {@code agent_runs} row, or null for task-less (planner) runs
 * @param runId             the run directory / harness session id
 */
public record AgentRunResult(
        String status,
        String failureReason,
        String lastAssistantText,
        RunEvent.Result result,
        BigDecimal costUsd,
        UUID agentRunId,
        UUID runId) {

    public boolean succeeded() {
        return "success".equals(status);
    }
}
