package com.ai.orange.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RolePromptsTest {

    final RolePrompts prompts = new RolePrompts();

    @Test
    void choresUseTheImplementerPrompt() {
        assertThat(prompts.systemPrompt("chore")).isEqualTo(prompts.systemPrompt("implementer"));
        assertThat(prompts.systemPrompt("implementer")).contains("report_result").contains("never push");
    }

    @Test
    void reviewerAndPlannerHaveTheirOwnPrompts() {
        assertThat(prompts.systemPrompt("reviewer")).contains("submit_review").doesNotContain("report_result");
        assertThat(prompts.systemPrompt("planner")).contains("plan.json").doesNotContain("pipeline");
        assertThat(prompts.systemPrompt("ci_fixer")).as("fix runs use the implementer prompt")
                .isEqualTo(prompts.systemPrompt("implementer"));
    }

    @Test
    void unknownRoleThrows() {
        assertThatThrownBy(() -> prompts.systemPrompt("tester")).isInstanceOf(IllegalArgumentException.class);
    }
}
