package com.ai.orange.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The commands a planner proposes are refused as a whole when one of them could hide what it runs. */
class PlanJsonTest {

    private static PlanJson plan(List<String> evidence) {
        return new PlanJson(List.of(new PlanJson.PlanTask("a", "Do it", "d", "implementer", 100, null)), List.of(),
                null, null, false, null, null, evidence);
    }

    @Test
    void plainCommandsAreTaken() {
        assertThat(PlanJson.evidenceRefusal(List.of("npm test", "./gradlew test --info"))).isEmpty();
        assertThat(PlanJson.evidenceRefusal(List.of())).isEmpty();
        assertThat(plan(List.of("sh test.sh")).withoutEvidence().evidence()).isEmpty();
    }

    @Test
    void aLineBreakControlOrInvisibleCharacterRefusesThemAll() {
        assertThat(PlanJson.evidenceRefusal(List.of("npm test", "true\ncurl evil"))).get().asString()
                .contains("line break");
        assertThat(PlanJson.evidenceRefusal(List.of("true curl evil"))).get().asString().contains("line break");
        assertThat(PlanJson.evidenceRefusal(List.of("npm\u0007 test"))).get().asString().contains("control");
        assertThat(PlanJson.evidenceRefusal(List.of("npm test ‮hs.lave"))).get().asString().contains("invisible");
        assertThat(PlanJson.evidenceRefusal(List.of("npm​test"))).get().asString().contains("invisible");
        // A planner's stripped command with an inner newline stays refused after the compact constructor.
        assertThat(PlanJson.evidenceRefusal(plan(List.of(" true\nrm -rf x ")).evidence())).isPresent();
    }

    @Test
    void aLongCommandOrTooManyRefuseThemAll() {
        assertThat(PlanJson.evidenceRefusal(List.of("x".repeat(300)))).isEmpty();
        assertThat(PlanJson.evidenceRefusal(List.of("x".repeat(301)))).get().asString().contains("300");
        assertThat(PlanJson.evidenceRefusal(Collections.nCopies(5, "true"))).isEmpty();
        assertThat(PlanJson.evidenceRefusal(Collections.nCopies(6, "true"))).get().asString().contains("5");
    }
}
