package com.ai.orange.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.json.Json;
import com.ai.orange.planner.PlanApproval.Decision;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** orange's rule on whether a plan dispatches at once or asks first (remote-first spec §7.2). */
class PlanApprovalTest {

    private static PlanJson.PlanTask task(String key, Boolean risk, String riskReason) {
        return new PlanJson.PlanTask(key, "Task " + key, null, null, null, null, risk, null, null, riskReason);
    }

    private static PlanJson plan(boolean askFirst, String reason, PlanJson.PlanTask... tasks) {
        return new PlanJson(List.of(tasks), List.of(), null, null, askFirst, reason);
    }

    private static PlanJson safe(int n) {
        List<PlanJson.PlanTask> tasks = new ArrayList<>();
        for (int i = 0; i < n; i++) tasks.add(task("t" + i, false, null));
        return plan(false, null, tasks.toArray(PlanJson.PlanTask[]::new));
    }

    @Test
    void noEvidenceAlwaysAsksEvenUnderNever() {
        assertThat(PlanApproval.decide("never", false, safe(1), 6))
                .isEqualTo(new Decision(true, "no tests in force yet"));
        assertThat(PlanApproval.decide("auto", false, safe(1), 6).ask()).isTrue();
    }

    @Test
    void alwaysAsks() {
        assertThat(PlanApproval.decide("always", true, safe(1), 6))
                .isEqualTo(new Decision(true, "plan-approval is always"));
    }

    @Test
    void neverDispatchesARiskyPlan() {
        PlanJson risky = plan(true, "big change", task("a", true, "touches the migrations"));
        assertThat(PlanApproval.decide("never", true, risky, 6)).isEqualTo(new Decision(false, null));
    }

    @Test
    void plannerAskFirstCarriesItsReason() {
        assertThat(PlanApproval.decide("auto", true, plan(true, "the request is ambiguous", task("a", null, null)), 6))
                .isEqualTo(new Decision(true, "the request is ambiguous"));
        assertThat(PlanApproval.decide("auto", true, plan(true, "  ", task("a", null, null)), 6))
                .isEqualTo(new Decision(true, "the planner asks first"));
    }

    @Test
    void aLargePlanAsks() {
        assertThat(PlanApproval.decide("auto", true, safe(7), 6))
                .isEqualTo(new Decision(true, "7 tasks (more than 6)"));
        assertThat(PlanApproval.decide("auto", true, safe(6), 6).ask()).isFalse();
    }

    @Test
    void aRiskyTaskAsksWithItsReason() {
        PlanJson risky = plan(false, null, task("a", false, null), task("b", true, "touches the migrations"),
                task("c", true, "deletes data"));
        assertThat(PlanApproval.decide("auto", true, risky, 6))
                .isEqualTo(new Decision(true, "Task b is risky: touches the migrations"));
        assertThat(PlanApproval.decide("auto", true, plan(false, null, task("a", true, null)), 6))
                .isEqualTo(new Decision(true, "Task a is risky: flagged by the planner"));
    }

    @Test
    void aSmallSafePlanDispatches() {
        assertThat(PlanApproval.decide("auto", true, safe(3), 6)).isEqualTo(new Decision(false, null));
        assertThat(PlanApproval.decide(null, true, safe(1), 6)).isEqualTo(new Decision(false, null));
    }

    @Test
    void anUnknownConventionIsAuto() {
        assertThat(PlanApproval.decide("sometimes", true, safe(1), 6)).isEqualTo(new Decision(false, null));
        assertThat(PlanApproval.decide("sometimes", true, safe(7), 6).ask()).isTrue();
        assertThat(PlanApproval.decide(" ALWAYS ", true, safe(1), 6).ask()).isTrue();
    }

    @Test
    void thePlannersFieldsParseFromSnakeCase() throws Exception {
        PlanJson parsed = Json.mapper().readValue("""
                {"tasks":[{"key":"a","title":"A","risk":true,"risk_reason":"touches the migrations"}],
                 "edges":[],"ask_first":true,"reason":"the request is ambiguous"}
                """, PlanJson.class);
        assertThat(parsed.askFirst()).isTrue();
        assertThat(parsed.reason()).isEqualTo("the request is ambiguous");
        assertThat(parsed.tasks().getFirst().riskReason()).isEqualTo("touches the migrations");
        assertThat(Json.mapper().readValue("{\"tasks\":[]}", PlanJson.class).askFirst()).isFalse();
    }
}
