package com.ai.orange.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/** A brief time or a quiet hour that is no time stops the start instead of silently meaning nothing. */
class ConventionsPropertiesTest {

    static ConventionsProperties with(List<String> briefTimes, List<String> quietHours) {
        return new ConventionsProperties(List.of("evidence", "integrated"), "merge", "routine", List.of(), 0.8,
                briefTimes, quietHours);
    }

    @Test
    void theDefaultsConstruct() {
        ConventionsProperties p = with(List.of("09:00", "14:00"), List.of("22:00", "08:00"));
        assertThat(p.get("brief-times").get(1).asText()).isEqualTo("14:00");
        assertThat(p.get("quiet-hours").get(0).asText()).isEqualTo("22:00");
    }

    @Test
    void aBriefTimeMustBeATime() {
        assertThatThrownBy(() -> with(List.of("09:00", "9:00"), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orange.conventions.brief-times: '9:00' is not a time like 09:00");
    }

    @Test
    void quietHoursAreTwoTimesOrNone() {
        assertThatThrownBy(() -> with(List.of(), List.of("22:00")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quiet-hours");
        assertThatThrownBy(() -> with(List.of(), List.of("22:00", "25:00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("orange.conventions.quiet-hours: '25:00' is not a time like 09:00");
        assertThatThrownBy(() -> with(List.of(), List.of("22:00", "08:00", "09:00")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quiet-hours");
        assertThat(with(List.of(), List.of()).quietHours()).isEmpty();
    }

    @Test
    void missingListsAreEmpty() {
        ConventionsProperties p = new ConventionsProperties(null, "merge", "routine", null, 0.8, null, null);
        assertThat(p.done()).isEmpty();
        assertThat(p.alwaysAsk()).isEmpty();
        assertThat(p.briefTimes()).isEmpty();
        assertThat(p.quietHours()).isEmpty();
        assertThat(p.asJson().path("brief-times")).isEmpty();
    }
}
