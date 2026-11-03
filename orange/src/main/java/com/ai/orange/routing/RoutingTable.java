package com.ai.orange.routing;

import com.ai.orange.routing.RoutingProperties.Rule;
import com.ai.orange.routing.RoutingProperties.TierSpec;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Pure rule-table routing (spec §10): matching rule, then the risk floor, then escalation.
 * The configuration is validated once, at construction, so a typo in a tier name fails startup
 * instead of the first run that hits it.
 */
@Component
public class RoutingTable {

    private final RoutingProperties props;
    private final List<PathMatcher> riskMatchers;

    public RoutingTable(RoutingProperties props) {
        this.props = props;
        validate(props);
        this.riskMatchers = props.risk() == null || props.risk().paths() == null
                ? List.of()
                : props.risk().paths().stream()
                        .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
                        .toList();
    }

    public RouteDecision route(RouteRequest r) {
        Rule rule = props.rules().stream()
                .filter(x -> x.role().equals(r.role()))
                .filter(x -> x.difficulty() == null || x.difficulty().equalsIgnoreCase(r.difficulty()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "no routing rule for role '" + r.role() + "' difficulty '" + r.difficulty() + "'"));

        List<String> order = props.tierOrder();
        int tierIdx = order.indexOf(rule.tier());
        Effort effort = rule.effort();

        if (r.risk() && props.risk() != null) {
            tierIdx = Math.max(tierIdx, order.indexOf(props.risk().minTier()));
            effort = Effort.max(effort, props.risk().minEffort());
        }

        if (props.escalation() != null && r.failures() >= props.escalation().tierUpAfterFailures()) {
            if (tierIdx < order.size() - 1) {
                tierIdx++;
            } else {
                effort = effort.raise();
            }
        }

        String tier = order.get(tierIdx);
        TierSpec spec = props.tiers().get(tier);
        return new RouteDecision(tier, spec.model(), effort, spec.harness(), spec.auth(), spec.budgetUsd());
    }

    /**
     * Whether a repo-relative path falls under a risk glob. Each glob is also tried against the
     * path with a leading {@code /}, so {@code **}{@code /auth/**} covers a top-level {@code auth/}
     * directory as well as nested ones.
     */
    public boolean isRiskPath(String repoRelativePath) {
        Path plain = Path.of(repoRelativePath);
        Path rooted = Path.of("/" + repoRelativePath);
        return riskMatchers.stream().anyMatch(m -> m.matches(plain) || m.matches(rooted));
    }

    private static void validate(RoutingProperties p) {
        if (p.tiers() == null || p.tiers().isEmpty()) {
            throw new IllegalStateException("orange.routing.tiers is empty");
        }
        if (p.rules() == null || p.rules().isEmpty()) {
            throw new IllegalStateException("orange.routing.rules is empty");
        }
        for (String tier : p.tiers().keySet()) {
            if (!p.tierOrder().contains(tier)) {
                throw new IllegalStateException("tier '" + tier + "' is missing from orange.routing.tier-order "
                        + p.tierOrder());
            }
        }
        for (String tier : p.tierOrder()) {
            if (!p.tiers().containsKey(tier)) {
                throw new IllegalStateException("tier-order names unknown tier '" + tier + "'");
            }
        }
        for (Rule rule : p.rules()) {
            if (!p.tiers().containsKey(rule.tier())) {
                throw new IllegalStateException("routing rule for role '" + rule.role()
                        + "' names unknown tier '" + rule.tier() + "'");
            }
            if (rule.effort() == null) {
                throw new IllegalStateException("routing rule for role '" + rule.role() + "' has no effort");
            }
        }
        if (p.risk() != null && p.risk().minTier() != null && !p.tiers().containsKey(p.risk().minTier())) {
            throw new IllegalStateException("risk min-tier names unknown tier '" + p.risk().minTier() + "'");
        }
    }
}
