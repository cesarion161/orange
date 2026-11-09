package com.ai.orange.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

class RunOutcomeTest {

    @Test
    void resultRoundTrips() {
        RunOutcome.Result result = new RunOutcome.Result("blocked", "Which DB?", "Which DB?");
        JSONB json = result.toJsonb();
        assertThat(json.data()).contains("\"type\":\"result\"");
        assertThat(RunOutcome.parse(json)).hasValue(result);
    }

    @Test
    void reviewRoundTrips() {
        RunOutcome.Review review = new RunOutcome.Review("gaps", "Two problems",
                List.of(new ReviewGap("README.md", 1, "typo"), new ReviewGap(null, null, "no tests")));
        JSONB json = review.toJsonb();
        assertThat(json.data()).contains("\"type\":\"review\"");
        assertThat(RunOutcome.parse(json)).hasValue(review);
    }

    @Test
    void malformedOrMissingParsesToEmpty() {
        assertThat(RunOutcome.parse(null)).isEmpty();
        assertThat(RunOutcome.parse(JSONB.valueOf("not json"))).isEmpty();
        assertThat(RunOutcome.parse(JSONB.valueOf("{\"type\":\"other\"}"))).isEmpty();
        assertThat(RunOutcome.parse(JSONB.valueOf("{}"))).isEmpty();
    }
}
