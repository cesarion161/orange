package com.ai.orange.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunsProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/** The shipped application.yaml must bind into a valid routing table with the spec §10 defaults. */
class RoutingPropertiesBindingTest {

    @Test
    void shippedDefaultsBindAndRoute() throws Exception {
        RoutingProperties props = shipped().bindOrCreate("orange.routing", RoutingProperties.class);

        RoutingTable table = new RoutingTable(props);

        RouteDecision implementerM = table.route(new RouteRequest("implementer", "M", false, 0));
        assertThat(implementerM.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(implementerM.effort()).isEqualTo(Effort.HIGH);
        assertThat(implementerM.auth()).isEqualTo(AuthMode.SUBSCRIPTION);
        assertThat(implementerM.budgetUsd()).isEqualByComparingTo("4");

        RouteDecision brief = table.route(new RouteRequest("brief", null, false, 0));
        assertThat(brief.model()).isEqualTo("claude-sonnet-5-5");
        assertThat(brief.effort()).isEqualTo(Effort.LOW);

        assertThat(table.route(new RouteRequest("planner", null, false, 0)).effort()).isEqualTo(Effort.XHIGH);
        assertThat(table.route(new RouteRequest("chore", "S", false, 0)).model()).isEqualTo("claude-haiku-5-5");
        assertThat(table.isRiskPath(".github/workflows/ci.yml")).isTrue();
        assertThat(props.smallModel()).isEqualTo("claude-haiku-5-5");
        assertThat(props.escalation().needsHumanAfterFailures()).isEqualTo(3);
    }

    /** Brief runs start directly: a short timeout so a hung one falls back in time, and no slot. */
    @Test
    void shippedRunSettingsGiveBriefRunsFiveMinutesAndNoSlot() throws Exception {
        RunsProperties runs = shipped().bindOrCreate("orange.runs", RunsProperties.class);

        assertThat(runs.timeout(RunKind.BRIEF)).isEqualTo(Duration.ofMinutes(5));
        assertThat(runs.slots(RunKind.BRIEF)).isZero();
    }

    private static Binder shipped() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        StandardEnvironment env = new StandardEnvironment();
        sources.forEach(env.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(env), new PropertySourcesPlaceholdersResolver(env));
    }
}
