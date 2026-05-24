package com.ai.orange.db;

import static com.ai.orange.db.jooq.Tables.AGENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Verifies the V13 migration's net effect: after all migrations apply, the
 * reviewer agent's prompt has the "verdict is binding" note exactly once (V10
 * seeds the reviewer, V13 appends the note idempotently).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ReviewerVerdictBindingMigrationIT {

    private static final String NOTE = "Your VERDICT is binding.";

    @Autowired DSLContext dsl;

    @Test
    void reviewer_prompt_marks_the_verdict_binding_exactly_once() {
        String prompt = dsl.select(AGENTS.SYSTEM_PROMPT).from(AGENTS)
                .where(AGENTS.NAME.eq("reviewer"))
                .fetchOne(0, String.class);

        assertThat(prompt).isNotNull().contains(NOTE);
        int occurrences = (prompt.length() - prompt.replace(NOTE, "").length()) / NOTE.length();
        assertThat(occurrences).as("V13 append must be idempotent").isEqualTo(1);
    }
}
