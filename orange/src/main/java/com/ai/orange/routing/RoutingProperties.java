package com.ai.orange.routing;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The routing rule table ({@code orange.routing}). Rules map a run's role and the task's
 * difficulty to a tier and an effort; tiers map to a model, a harness, an auth mode and a
 * per-run budget. See spec §10.
 *
 * @param tierOrder  tiers from cheapest to most capable; escalation moves one step right
 * @param tiers      tier name → what that tier runs on
 * @param rules      first match wins; a rule with a null difficulty matches any difficulty
 * @param risk       floor applied to risky tasks, and the path globs that make a task risky
 * @param escalation failure thresholds
 * @param smallModel the model Claude Code uses for its own background calls
 */
@ConfigurationProperties(prefix = "orange.routing")
public record RoutingProperties(
        @DefaultValue({"small", "medium", "large"}) List<String> tierOrder,
        Map<String, TierSpec> tiers,
        List<Rule> rules,
        Risk risk,
        Escalation escalation,
        String smallModel) {

    /**
     * @param budgetUsd per-run cap: an estimate watched by the runner for subscription runs,
     *                  the virtual key's {@code max_budget} for gateway runs
     */
    public record TierSpec(String model, String harness, AuthMode auth, BigDecimal budgetUsd) {}

    public record Rule(String role, String difficulty, String tier, Effort effort) {}

    public record Risk(String minTier, Effort minEffort, List<String> paths) {}

    public record Escalation(int tierUpAfterFailures, int needsHumanAfterFailures) {}
}
