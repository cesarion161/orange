package com.ai.orange.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.json.Json;
import com.ai.orange.run.ReviewPolicy.Mode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ReviewPolicyTest {

    @ParameterizedTest(name = "{0}, task asks {1}, risky {2} → {3}")
    @CsvSource({
            "NEVER,  false, false, false",
            "NEVER,  true,  false, false",
            "NEVER,  false, true,  false",
            "NEVER,  true,  true,  false",
            "RISKY,  false, false, false",
            "RISKY,  true,  false, true",
            "RISKY,  false, true,  true",
            "RISKY,  true,  true,  true",
            "ALWAYS, false, false, true",
            "ALWAYS, true,  false, true",
            "ALWAYS, false, true,  true",
            "ALWAYS, true,  true,  true"})
    void requiredFollowsTheModeTheTaskAndTheRisk(Mode mode, boolean taskAsks, boolean risky, boolean required) {
        assertThat(ReviewPolicy.required(mode, taskAsks, risky)).isEqualTo(required);
    }

    @Test
    void parseReadsTheThreeValuesAndDefaultsToRisky() throws Exception {
        assertThat(Mode.parse(Json.mapper().readTree("\"never\""))).isEqualTo(Mode.NEVER);
        assertThat(Mode.parse(Json.mapper().readTree("\"always\""))).isEqualTo(Mode.ALWAYS);
        assertThat(Mode.parse(Json.mapper().readTree("\"risky\""))).isEqualTo(Mode.RISKY);
        // Anything else is the default, never a crash and never a silently skipped review.
        assertThat(Mode.parse(Json.mapper().readTree("\"Always\""))).isEqualTo(Mode.RISKY);
        assertThat(Mode.parse(Json.mapper().readTree("true"))).isEqualTo(Mode.RISKY);
        assertThat(Mode.parse(Json.mapper().readTree("[\"risky\"]"))).isEqualTo(Mode.RISKY);
        assertThat(Mode.parse(MissingNode.getInstance())).isEqualTo(Mode.RISKY);
        assertThat(Mode.parse(null)).isEqualTo(Mode.RISKY);
    }
}
