package com.ai.orange.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class PlanLimitTest {

    @Test
    void planLimitMatches() {
        assertThat(PlanLimit.matches("Claude usage limit reached. Your limit will reset at 5pm (Europe/Berlin).")).isTrue();
        assertThat(PlanLimit.matches("5-hour limit reached ∙ resets 3am — your limit will reset at 3am")).isTrue();
        assertThat(PlanLimit.matches("CLAUDE USAGE LIMIT REACHED")).isTrue();
    }

    @Test
    void otherErrorsDoNotMatch() {
        assertThat(PlanLimit.matches("rate limited, retrying")).isFalse();
        assertThat(PlanLimit.matches("API Error: 529 overloaded")).isFalse();
        assertThat(PlanLimit.matches(null)).isFalse();
        assertThat(PlanLimit.matches("")).isFalse();
    }

    @Test
    void resetsAtParsesTheResetTime() {
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 9, 13, 0, 0, 0, ZoneId.of("Europe/Berlin"));

        assertThat(PlanLimit.resetsAt("Claude usage limit reached. Your limit will reset at 5pm (Europe/Berlin).", now))
                .hasValue(ZonedDateTime.of(2026, 11, 9, 17, 0, 0, 0, ZoneId.of("Europe/Berlin")));
        assertThat(PlanLimit.resetsAt("5-hour limit reached ∙ resets 3am", now))
                .as("3am has passed today, so tomorrow; no zone named, so now's")
                .hasValue(ZonedDateTime.of(2026, 11, 10, 3, 0, 0, 0, ZoneId.of("Europe/Berlin")));
        assertThat(PlanLimit.resetsAt("Usage limit reached, resets 5:30pm (UTC)", now))
                .hasValue(ZonedDateTime.of(2026, 11, 9, 17, 30, 0, 0, ZoneId.of("UTC")));
        assertThat(PlanLimit.resetsAt("resets 12am (Europe/Berlin)", now))
                .hasValue(ZonedDateTime.of(2026, 11, 10, 0, 0, 0, 0, ZoneId.of("Europe/Berlin")));
    }

    @Test
    void resetsAtIsEmptyWithoutATime() {
        ZonedDateTime now = ZonedDateTime.now();
        assertThat(PlanLimit.resetsAt("Claude usage limit reached.", now)).isEmpty();
        assertThat(PlanLimit.resetsAt(null, now)).isEmpty();
        assertThat(PlanLimit.resetsAt("resets 5pm (Not/AZone)", now)).as("unknown zone falls back to now's zone")
                .hasValueSatisfying(t -> assertThat(t.getZone()).isEqualTo(now.getZone()));
    }
}
