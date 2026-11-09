package com.ai.orange.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.json.Json;
import com.ai.orange.run.ReactorDecision.Action;
import com.ai.orange.run.ReactorDecision.LastRun;
import com.ai.orange.run.ReactorDecision.TaskSnapshot;
import com.ai.orange.task.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;

/** One test per reactor rule, plus the precedence between them. */
class ReactorDecisionTest {

    static final int NEEDS_HUMAN_AFTER = 3;
    static final String HEAD = "abc123";

    /** A snapshot builder: everything off, the workspace available. */
    static final class S {
        TaskStatus status;
        int failures;
        boolean active;
        boolean paused;
        boolean workspace = true;
        String head;
        LastRun last;
        boolean verify;
        boolean review;

        S(TaskStatus status) {
            this.status = status;
        }

        S failures(int n) { failures = n; return this; }
        S active() { active = true; return this; }
        S paused() { paused = true; return this; }
        S noWorkspace() { workspace = false; return this; }
        S head(String h) { head = h; return this; }
        S last(LastRun r) { last = r; return this; }
        S verifyPassed() { verify = true; return this; }
        S reviewPassed() { review = true; return this; }

        Action decide() {
            return ReactorDecision.decide(new TaskSnapshot(status, failures, active, paused, workspace, head, last,
                    verify, review), NEEDS_HUMAN_AFTER);
        }
    }

    static S task(TaskStatus status) {
        return new S(status);
    }

    static LastRun run(RunKind kind, RunStatus status, RunOutcome outcome, String input) {
        return new LastRun(kind, status, outcome == null ? null : outcome.toJsonb(), JSONB.valueOf(input));
    }

    static JsonNode feedback(JSONB json) throws Exception {
        return Json.mapper().readTree(json.data());
    }

    // ── rule 1 ──

    @Test
    void terminalAndPendingTasksAreLeftAlone() {
        for (TaskStatus s : List.of(TaskStatus.PENDING, TaskStatus.INTEGRATED, TaskStatus.FAILED, TaskStatus.CANCELLED)) {
            assertThat(task(s).decide()).as(s.dbValue()).isEqualTo(new Action.None());
        }
    }

    @Test
    void anActiveRunBeatsEverything() {
        assertThat(task(TaskStatus.READY).active().decide()).isEqualTo(new Action.None());
        assertThat(task(TaskStatus.RUNNING).active().failures(9).decide()).isEqualTo(new Action.None());
        assertThat(task(TaskStatus.VERIFIED).active().head(HEAD).decide()).isEqualTo(new Action.None());
    }

    // ── rule 2 ──

    @Test
    void pausedBeatsQueueingAndGivingUp() {
        assertThat(task(TaskStatus.READY).paused().decide()).isEqualTo(new Action.None());
        assertThat(task(TaskStatus.RUNNING).paused().failures(3).decide()).isEqualTo(new Action.None());
        assertThat(task(TaskStatus.VERIFIED).paused().head(HEAD).decide()).isEqualTo(new Action.None());
    }

    // ── rule 3 ──

    @Test
    void failuresBeatTheRetry() {
        for (TaskStatus s : List.of(TaskStatus.RUNNING, TaskStatus.VERIFYING, TaskStatus.VERIFIED)) {
            assertThat(task(s).failures(3).head(HEAD).decide()).as(s.dbValue())
                    .isEqualTo(new Action.NeedsHuman("failures"));
        }
        assertThat(task(TaskStatus.RUNNING).failures(2).decide()).isInstanceOf(Action.QueueImplement.class);
    }

    // ── rule 4 ──

    @Test
    void readyTaskWithAWorkspaceGetsAnImplementRun() {
        assertThat(task(TaskStatus.READY).decide()).isEqualTo(new Action.QueueImplement(null));
    }

    @Test
    void readyTaskWithoutAWorktreeSlotWaits() {
        assertThat(task(TaskStatus.READY).noWorkspace().decide()).isEqualTo(new Action.None());
    }

    // ── rule 5 ──

    @Test
    void verifyGapsQueueAFix() throws Exception {
        RunOutcome.Verification gaps = new RunOutcome.Verification(false, false, List.of(
                new EvidenceGap("tests", "./gradlew test", 1, "FooTest > bar FAILED"),
                new EvidenceGap("custom", EvidenceRunner.STATUS_COMMAND, null, "?? scratch.txt")));

        Action a = task(TaskStatus.RUNNING).last(run(RunKind.VERIFY, RunStatus.SUCCEEDED, gaps, "{}")).decide();

        assertThat(a).isInstanceOf(Action.QueueFix.class);
        JsonNode items = feedback(((Action.QueueFix) a).feedback());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("source").asText()).isEqualTo("tests");
        assertThat(items.get(0).path("summary").asText()).contains("./gradlew test").contains("exit 1");
        assertThat(items.get(0).path("detail").asText()).isEqualTo("FooTest > bar FAILED");
        assertThat(items.get(1).path("summary").asText()).contains("ncommitted");
        assertThat(items.get(1).path("detail").asText()).contains("scratch.txt");
    }

    @Test
    void anIntegrationConflictQueuesAFix() throws Exception {
        RunOutcome.Integration conflict = new RunOutcome.Integration("conflict", null, null, null,
                List.of("README.md", "src/A.java"), null);

        Action a = task(TaskStatus.RUNNING).last(run(RunKind.INTEGRATE, RunStatus.SUCCEEDED, conflict, "{}")).decide();

        JsonNode item = feedback(((Action.QueueFix) a).feedback()).get(0);
        assertThat(item.path("source").asText()).isEqualTo("integration");
        assertThat(item.path("summary").asText()).contains("README.md").contains("src/A.java");
    }

    @Test
    void aFailedFixRetriesWithItsOwnInput() throws Exception {
        String input = "{\"feedback\":[{\"source\":\"tests\",\"summary\":\"x failed\",\"detail\":\"boom\"}]}";

        Action a = task(TaskStatus.RUNNING).last(run(RunKind.CI_FIX, RunStatus.FAILED, null, input)).decide();

        assertThat(feedback(((Action.QueueFix) a).feedback()).get(0).path("summary").asText()).isEqualTo("x failed");
    }

    @Test
    void reviewGapsQueueReworkNotAFix() throws Exception {
        RunOutcome.Review gaps = new RunOutcome.Review("gaps", "Two problems", List.of(
                new ReviewGap("README.md", 1, "The heading has a typo."), new ReviewGap(null, null, "No test.")));

        Action a = task(TaskStatus.RUNNING).last(run(RunKind.DIFF_REVIEW, RunStatus.SUCCEEDED, gaps, "{}")).decide();

        assertThat(a).isInstanceOf(Action.QueueImplement.class);
        JsonNode items = feedback(((Action.QueueImplement) a).feedback());
        assertThat(items).hasSize(2);
        assertThat(items.get(0).path("source").asText()).isEqualTo("diff_review");
        assertThat(items.get(0).path("summary").asText()).isEqualTo("README.md:1: The heading has a typo.");
        assertThat(items.get(1).path("summary").asText()).isEqualTo("No test.");
    }

    @Test
    void aFailedImplementRetriesWithItsOwnInput() throws Exception {
        String input = "{\"feedback\":[{\"source\":\"diff_review\",\"summary\":\"No test.\"}]}";

        Action a = task(TaskStatus.RUNNING).last(run(RunKind.IMPLEMENT, RunStatus.FAILED, null, input)).decide();

        assertThat(feedback(((Action.QueueImplement) a).feedback()).get(0).path("summary").asText()).isEqualTo("No test.");
    }

    @Test
    void aRunningTaskWithNothingToGoOnGetsAPlainImplementRun() {
        assertThat(task(TaskStatus.RUNNING).decide()).isEqualTo(new Action.QueueImplement(null));
        assertThat(task(TaskStatus.RUNNING).last(run(RunKind.IMPLEMENT, RunStatus.FAILED, null, "{}")).decide())
                .isEqualTo(new Action.QueueImplement(null));
    }

    // ── rule 6 ──

    @Test
    void verifyingRunsTheChecksThenTheReviewThenMarksVerified() {
        assertThat(task(TaskStatus.VERIFYING).head(HEAD).decide()).isEqualTo(new Action.QueueVerify(HEAD));
        assertThat(task(TaskStatus.VERIFYING).head(HEAD).verifyPassed().decide())
                .isEqualTo(new Action.QueueDiffReview(HEAD));
        assertThat(task(TaskStatus.VERIFYING).head(HEAD).verifyPassed().reviewPassed().decide())
                .isEqualTo(new Action.MarkVerified());
    }

    @Test
    void verifyingWithoutAHeadIsLeftAlone() {
        assertThat(task(TaskStatus.VERIFYING).decide()).isEqualTo(new Action.None());
    }

    @Test
    void aWorktreeThatMovedUnderVerificationNeedsAPerson() {
        LastRun staleVerify = run(RunKind.VERIFY, RunStatus.SUCCEEDED, RunOutcome.Verification.ofStale(), "{}");
        LastRun staleIntegrate = run(RunKind.INTEGRATE, RunStatus.SUCCEEDED, RunOutcome.Integration.of("stale"), "{}");

        assertThat(task(TaskStatus.VERIFYING).head(HEAD).last(staleVerify).decide())
                .isEqualTo(new Action.NeedsHuman("worktree_moved"));
        assertThat(task(TaskStatus.VERIFIED).head(HEAD).last(staleIntegrate).decide())
                .isEqualTo(new Action.NeedsHuman("worktree_moved"));
    }

    // ── rule 7 ──

    @Test
    void verifiedGetsIntegrated() {
        assertThat(task(TaskStatus.VERIFIED).head(HEAD).decide()).isEqualTo(new Action.QueueIntegrate(HEAD));
        LastRun moved = run(RunKind.INTEGRATE, RunStatus.SUCCEEDED, RunOutcome.Integration.of("base_moved"), "{}");
        assertThat(task(TaskStatus.VERIFIED).head(HEAD).last(moved).decide())
                .as("a push that lost a race is simply tried again").isEqualTo(new Action.QueueIntegrate(HEAD));
    }

    // ── rule 8 ──

    @Test
    void needsHumanWaitsForAPerson() {
        assertThat(task(TaskStatus.NEEDS_HUMAN).decide()).isEqualTo(new Action.None());
    }
}
