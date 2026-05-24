package com.ai.orange.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Pure unit tests for the reviewer verdict parsing + the gating it drives. */
class ReviewerVerdictTest {

    @Test
    void parses_pass_and_fail_from_a_trailing_verdict_line() {
        assertThat(PipelineActivitiesImpl.parseReviewVerdict(
                "Summary of the change...\n- a finding\n\nVERDICT: PASS")).isEqualTo("pass");
        assertThat(PipelineActivitiesImpl.parseReviewVerdict(
                "BLOCKING: leaks a connection\nVERDICT: FAIL")).isEqualTo("fail");
    }

    @Test
    void is_case_insensitive_and_ignores_prose_mentions() {
        // A prose line that merely mentions the word is ignored; only a line that
        // starts with VERDICT counts.
        assertThat(PipelineActivitiesImpl.parseReviewVerdict(
                "my verdict will follow; ignore pass/fail talk here\nverdict: fail"))
                .isEqualTo("fail");
    }

    @Test
    void last_verdict_line_wins() {
        assertThat(PipelineActivitiesImpl.parseReviewVerdict(
                "VERDICT: FAIL (initial)\n...reconsidered...\nVERDICT: PASS")).isEqualTo("pass");
    }

    @Test
    void null_when_no_verdict_line() {
        assertThat(PipelineActivitiesImpl.parseReviewVerdict("looks good to me")).isNull();
        assertThat(PipelineActivitiesImpl.parseReviewVerdict("")).isNull();
        assertThat(PipelineActivitiesImpl.parseReviewVerdict(null)).isNull();
    }

    @Test
    void fail_verdict_blocks_the_stage_pass_and_absent_do_not() {
        // The gate: a reviewer FAIL → StageOutcome not succeeded → retryPrevious
        // bounces back to dev. PASS and a missing verdict pass through.
        assertThat(new StageOutcome("success", "fail", "review").succeeded()).isFalse();
        assertThat(new StageOutcome("success", "pass", "review").succeeded()).isTrue();
        assertThat(new StageOutcome("success", null, "review").succeeded()).isTrue();
    }
}
