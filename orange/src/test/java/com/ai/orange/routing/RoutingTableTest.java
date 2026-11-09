package com.ai.orange.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.routing.RoutingProperties.Escalation;
import com.ai.orange.routing.RoutingProperties.Risk;
import com.ai.orange.routing.RoutingProperties.Rule;
import com.ai.orange.routing.RoutingProperties.TierSpec;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingTableTest {

    /** The spec §10 defaults, written out by hand (not read from application.yaml). */
    static RoutingProperties defaults(List<Rule> rules) {
        Map<String, TierSpec> tiers = new LinkedHashMap<>();
        tiers.put("large", new TierSpec("claude-opus-5-5", "claude-code", AuthMode.SUBSCRIPTION, new BigDecimal("8")));
        tiers.put("medium", new TierSpec("claude-sonnet-5-5", "claude-code", AuthMode.SUBSCRIPTION, new BigDecimal("4")));
        tiers.put("small", new TierSpec("claude-haiku-5-5", "claude-code", AuthMode.SUBSCRIPTION, new BigDecimal("1")));
        return new RoutingProperties(
                List.of("small", "medium", "large"),
                tiers,
                rules,
                new Risk("large", Effort.HIGH, List.of("**/auth/**", "**/migrations/**", "**/db/migration/**",
                        "**/payments/**", "infra/**", ".github/workflows/**")),
                new Escalation(2, 3),
                "claude-haiku-5-5");
    }

    static List<Rule> defaultRules() {
        return List.of(
                new Rule("planner", null, "large", Effort.XHIGH),
                new Rule("reviewer", null, "large", Effort.HIGH),
                new Rule("ci_fixer", null, "medium", Effort.MEDIUM),
                new Rule("chore", null, "small", Effort.LOW),
                new Rule("implementer", "L", "large", Effort.HIGH),
                new Rule("implementer", "M", "medium", Effort.HIGH),
                new Rule("implementer", "S", "medium", Effort.MEDIUM),
                new Rule("tester", null, "medium", Effort.MEDIUM),
                new Rule("triage", null, "medium", Effort.MEDIUM));
    }

    final RoutingTable table = new RoutingTable(defaults(defaultRules()));

    @Test
    void implementerByDifficulty() {
        RouteDecision small = table.route(new RouteRequest("implementer", "S", false, 0));
        assertThat(small.tier()).isEqualTo("medium");
        assertThat(small.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(small.effort()).isEqualTo(Effort.MEDIUM);
        assertThat(small.auth()).isEqualTo(AuthMode.SUBSCRIPTION);
        assertThat(small.budgetUsd()).isEqualByComparingTo("4");

        RouteDecision large = table.route(new RouteRequest("implementer", "L", false, 0));
        assertThat(large.tier()).isEqualTo("large");
        assertThat(large.model()).isEqualTo("claude-opus-5-5");
        assertThat(large.effort()).isEqualTo(Effort.HIGH);
        assertThat(large.harness()).isEqualTo("claude-code");
    }

    @Test
    void firstMatchWins_andAnyDifficulty() {
        RouteDecision reviewer = table.route(new RouteRequest("reviewer", "S", false, 0));
        assertThat(reviewer.tier()).isEqualTo("large");
        assertThat(reviewer.effort()).isEqualTo(Effort.HIGH);

        assertThat(table.route(new RouteRequest("planner", "M", false, 0)).effort()).isEqualTo(Effort.XHIGH);

        // A more specific rule listed after a catch-all for the same role never wins.
        List<Rule> rules = new ArrayList<>(defaultRules());
        rules.add(0, new Rule("chore", null, "medium", Effort.HIGH));
        RoutingTable shadowed = new RoutingTable(defaults(rules));
        assertThat(shadowed.route(new RouteRequest("chore", "S", false, 0)).tier()).isEqualTo("medium");
    }

    @Test
    void riskFloorsTierAndEffort() {
        RouteDecision d = table.route(new RouteRequest("chore", "S", true, 0));
        assertThat(d.tier()).isEqualTo("large");
        assertThat(d.model()).isEqualTo("claude-opus-5-5");
        assertThat(d.effort()).isEqualTo(Effort.HIGH);

        // The floor never lowers a decision that is already above it.
        assertThat(table.route(new RouteRequest("planner", "M", true, 0)).effort()).isEqualTo(Effort.XHIGH);
    }

    @Test
    void escalatesTierAfterTwoFailures() {
        RouteDecision one = table.route(new RouteRequest("implementer", "S", false, 1));
        assertThat(one.tier()).isEqualTo("medium");

        RouteDecision d = table.route(new RouteRequest("implementer", "S", false, 2));
        assertThat(d.tier()).isEqualTo("large");
        assertThat(d.model()).isEqualTo("claude-opus-5-5");
        assertThat(d.effort()).isEqualTo(Effort.MEDIUM);
        assertThat(d.budgetUsd()).isEqualByComparingTo("8");
    }

    @Test
    void escalatesEffortAtTopTier() {
        RouteDecision d = table.route(new RouteRequest("implementer", "L", false, 2));
        assertThat(d.tier()).isEqualTo("large");
        assertThat(d.effort()).isEqualTo(Effort.XHIGH);

        assertThat(table.route(new RouteRequest("planner", "L", false, 2)).effort()).isEqualTo(Effort.MAX);
    }

    @Test
    void unknownRoleThrows() {
        assertThatThrownBy(() -> table.route(new RouteRequest("astronaut", "M", false, 0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("astronaut");
    }

    @Test
    void riskPaths() {
        assertThat(table.isRiskPath("svc/auth/Login.java")).isTrue();
        assertThat(table.isRiskPath(".github/workflows/ci.yml")).isTrue();
        assertThat(table.isRiskPath("src/main/App.java")).isFalse();
    }

    @Test
    void riskPathsAtRepoRoot() {
        // "**/auth/**" must also cover a top-level auth/ directory.
        assertThat(table.isRiskPath("auth/Login.java")).isTrue();
        assertThat(table.isRiskPath("db/migration/V1__init.sql")).isTrue();
        assertThat(table.isRiskPath("authors.md")).isFalse();
    }

    @Test
    void badConfigFailsFast() {
        List<Rule> rules = new ArrayList<>(defaultRules());
        rules.add(new Rule("chore", null, "huge", Effort.LOW));
        assertThatThrownBy(() -> new RoutingTable(defaults(rules)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("huge");
    }

    @Test
    void tierOrderMustCoverAllTiers() {
        RoutingProperties p = defaults(defaultRules());
        RoutingProperties missing = new RoutingProperties(List.of("small", "large"), p.tiers(), p.rules(),
                p.risk(), p.escalation(), p.smallModel());
        assertThatThrownBy(() -> new RoutingTable(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("medium");
    }

    @Test
    void effortRaiseAndParse() {
        assertThat(Effort.MAX.raise()).isEqualTo(Effort.MAX);
        assertThat(Effort.HIGH.raise()).isEqualTo(Effort.XHIGH);
        assertThat(Effort.parse("xhigh")).isEqualTo(Effort.XHIGH);
        assertThat(Effort.XHIGH.cli()).isEqualTo("xhigh");
        assertThatThrownBy(() -> Effort.parse("HIGHEST")).isInstanceOf(IllegalArgumentException.class);
    }
}
