package com.ai.orange.billing;

import static org.assertj.core.api.Assertions.assertThat;

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
}
