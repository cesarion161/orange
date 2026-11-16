package com.ai.orange.planner;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.planner.PlannerPrompt.Facts;
import com.ai.orange.planner.PlannerPrompt.Milestone;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the planner is told: the project's facts first, then the user's request (R4). */
class PlannerPromptTest {

    private static Facts facts(List<String> evidence, List<Milestone> milestones, boolean cloned) {
        return new Facts("shop", List.of("me/shop"), "main", List.of("Customers can pay by card"), milestones,
                List.of("Postgres, not SQLite"), List.of("The API is versioned under /v1"), evidence, "risky",
                cloned);
    }

    @Test
    void factsComeBeforeTheRequest() {
        String prompt = PlannerPrompt.build("Add a checkout page", null, facts(List.of("sh test.sh"), List.of(), true));

        assertThat(prompt).contains("shop", "me/shop", "main", "Customers can pay by card", "Postgres, not SQLite",
                "The API is versioned under /v1", "sh test.sh", "risky");
        assertThat(prompt).contains("The repository is checked out read-only at repo/");
        int request = prompt.indexOf("Add a checkout page");
        assertThat(request).isGreaterThan(prompt.indexOf("Customers can pay by card"));
        assertThat(request).isGreaterThan(prompt.indexOf("sh test.sh"));
        assertThat(prompt).doesNotContain("evidence in force: none");
        assertThat(PlannerPrompt.build("x", null, facts(List.of(), List.of(), false)))
                .doesNotContain("checked out read-only");
    }

    @Test
    void saysWhenNoEvidenceIsInForce() {
        assertThat(PlannerPrompt.build("Add a checkout page", null, facts(List.of(), List.of(), true)))
                .contains("evidence in force: none");
        assertThat(PlannerPrompt.build("Add a checkout page", null, facts(null, List.of(), true)))
                .contains("evidence in force: none");
    }

    @Test
    void listsMilestonesWithTheirIds() {
        String prompt = PlannerPrompt.build("Add a checkout page", null, facts(List.of("sh test.sh"),
                List.of(new Milestone("0193-aaaa", "beta", "2026-12-01"), new Milestone("0193-bbbb", "launch", null)),
                true));

        assertThat(prompt).contains("0193-aaaa", "beta", "2026-12-01", "0193-bbbb", "launch");
        assertThat(PlannerPrompt.build("x", null, facts(List.of(), List.of(), true))).contains("milestones: none");
    }

    @Test
    void carriesTheRevisionNote() {
        String prompt = PlannerPrompt.build("Add a checkout page", "Split the payment task in two",
                facts(List.of("sh test.sh"), List.of(), true));

        assertThat(prompt).contains("The user asked for this revision of an earlier plan:");
        assertThat(prompt.indexOf("Split the payment task in two"))
                .isGreaterThan(prompt.indexOf("The user asked for this revision of an earlier plan:"))
                .isGreaterThan(prompt.indexOf("Add a checkout page"));
        assertThat(PlannerPrompt.build("Add a checkout page", " ", facts(List.of(), List.of(), true)))
                .doesNotContain("revision of an earlier plan");
    }
}
