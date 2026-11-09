package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.SETTINGS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchedulerPauseIT {

    @Autowired SchedulerPause pause;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        dsl.deleteFrom(SETTINGS).execute();
    }

    @Test
    void timedPauseExpires() {
        pause.pause("plan_limit", OffsetDateTime.now().plusHours(1));
        assertThat(pause.current()).hasValueSatisfying(p -> {
            assertThat(p.reason()).isEqualTo("plan_limit");
            assertThat(p.until()).isAfter(OffsetDateTime.now());
            assertThat(p.since()).isBeforeOrEqualTo(OffsetDateTime.now());
        });

        pause.resume();
        pause.pause("gateway_unavailable", OffsetDateTime.now().minusMinutes(1));
        assertThat(pause.current()).as("a pause past its until is over").isEmpty();
        assertThat(dsl.fetchCount(SETTINGS)).as("and cleared").isZero();
    }

    @Test
    void openEndedPauseSurvivesATimedOne() {
        pause.pause("auth_failed", null);
        pause.pause("plan_limit", OffsetDateTime.now().plusHours(2));
        assertThat(pause.current()).hasValueSatisfying(p -> {
            assertThat(p.reason()).isEqualTo("auth_failed");
            assertThat(p.until()).isNull();
        });
    }

    @Test
    void laterUntilWins() {
        OffsetDateTime soon = OffsetDateTime.now().plusMinutes(5);
        OffsetDateTime later = OffsetDateTime.now().plusHours(3);
        pause.pause("gateway_unavailable", soon);
        pause.pause("plan_limit", later);
        assertThat(pause.current()).hasValueSatisfying(p -> assertThat(p.reason()).isEqualTo("plan_limit"));

        pause.pause("gateway_unavailable", soon);
        assertThat(pause.current()).as("an earlier until does not shorten the pause")
                .hasValueSatisfying(p -> assertThat(p.reason()).isEqualTo("plan_limit"));

        pause.pause("start_failed", null);
        assertThat(pause.current()).as("open-ended beats timed")
                .hasValueSatisfying(p -> assertThat(p.until()).isNull());
    }

    @Test
    void resumeClears() {
        pause.pause("operator", null);
        pause.resume();
        assertThat(pause.current()).isEmpty();
    }
}
