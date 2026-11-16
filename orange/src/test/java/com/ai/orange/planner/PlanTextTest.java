package com.ai.orange.planner;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlanTextTest {

    private static PlanJson plan(List<String> evidence) {
        return new PlanJson(List.of(
                new PlanJson.PlanTask("auth", "Build auth\nmodule", "JWT.", "implementer", 100, null, true, null, null,
                        "touches the migrations"),
                new PlanJson.PlanTask("ui", "Build UI", "Views.", "implementer", 100, null),
                new PlanJson.PlanTask("tests", "Write tests", "Happy paths.", "implementer", 100, null, true, null)),
                List.of(new PlanJson.PlanEdge("auth", "ui"), new PlanJson.PlanEdge("ui", "tests")),
                null, null, false, null, null, evidence);
    }

    @Test
    void listsTasksRiskAndProposedEvidence() {
        String body = PlanText.body("Build a TODO app", "no tests in force yet", plan(List.of("sh test.sh")), false);

        assertThat(body).contains("Build a TODO app");
        assertThat(body).contains("no tests in force yet");
        // One line per task, a task's title on one line, its risk and reason marked.
        assertThat(body).contains("- auth: Build auth module (risky: touches the migrations)");
        assertThat(body).contains("- ui: Build UI\n");
        assertThat(body).contains("- tests: Write tests (risky: flagged by the planner)");
        assertThat(body).contains("auth → ui").contains("ui → tests");
        assertThat(body).contains("sh test.sh").contains("approving puts");
        assertThat(body).contains("approve, drop, or what to change");
    }

    @Test
    void evidenceAlreadyInForceIsNotPutInForceByApproving() {
        String body = PlanText.body("Build", "3 tasks (more than 2)", plan(List.of("sh test.sh")), true);

        assertThat(body).contains("sh test.sh").doesNotContain("approving puts");
    }

    @Test
    void aPlanWithoutEdgesOrEvidenceSaysLess() {
        PlanJson one = new PlanJson(List.of(new PlanJson.PlanTask("a", "Do it", "d", "implementer", 100, null)),
                List.of());

        String body = PlanText.body("Do it", "plan-approval is always", one, true);

        assertThat(body).contains("- a: Do it").doesNotContain("→").doesNotContain("evidence");
    }

    @Test
    void eachCommandIsOnItsOwnLineInBackticks() {
        String body = PlanText.body("Build", "no tests in force yet", plan(List.of("npm test", "echo `date`")), false);

        assertThat(body).contains("- `npm test`\n").contains("- `` echo `date` ``\n");
    }

    @Test
    void refusedCommandsAreSaidWhy() {
        String body = PlanText.body("Build", "no tests in force yet", plan(List.of()), false,
                "a command has a line break");

        assertThat(body).contains("orange refused the planner's proposed commands: a command has a line break");
        assertThat(body).doesNotContain("approving puts");
    }
}
