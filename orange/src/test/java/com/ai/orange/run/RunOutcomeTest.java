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
    void verificationRoundTrips() {
        RunOutcome.Verification gaps = new RunOutcome.Verification(false, false, List.of(
                new EvidenceGap("tests", "./gradlew test", 1, "FooTest > bar FAILED"),
                new EvidenceGap("custom", "git status --porcelain", null, "?? scratch.txt")));
        assertThat(gaps.toJsonb().data()).contains("\"type\":\"verification\"").contains("\"passed\":false");
        assertThat(RunOutcome.parse(gaps.toJsonb())).hasValue(gaps);
        assertThat(RunOutcome.parse(RunOutcome.Verification.ofStale().toJsonb())).hasValue(RunOutcome.Verification.ofStale());
    }

    @Test
    void integrationRoundTrips() {
        RunOutcome.Integration integrated = new RunOutcome.Integration("integrated", "abc", true, "dirty", List.of(), null);
        RunOutcome.Integration conflict = new RunOutcome.Integration("conflict", null, null, null,
                List.of("README.md", "src/A.java"), null);
        RunOutcome.Integration failed = new RunOutcome.Integration("failed", "abc", null, null, List.of(), "push hung");
        for (RunOutcome.Integration o : List.of(integrated, conflict, failed, RunOutcome.Integration.of("base_moved"))) {
            assertThat(o.toJsonb().data()).contains("\"type\":\"integration\"");
            assertThat(RunOutcome.parse(o.toJsonb())).hasValue(o);
        }
    }

    @Test
    void malformedOrMissingParsesToEmpty() {
        assertThat(RunOutcome.parse(null)).isEmpty();
        assertThat(RunOutcome.parse(JSONB.valueOf("not json"))).isEmpty();
        assertThat(RunOutcome.parse(JSONB.valueOf("{\"type\":\"other\"}"))).isEmpty();
        assertThat(RunOutcome.parse(JSONB.valueOf("{}"))).isEmpty();
    }
}
