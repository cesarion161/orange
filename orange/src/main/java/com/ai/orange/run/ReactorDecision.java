package com.ai.orange.run;

import com.ai.orange.json.Json;
import com.ai.orange.task.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.EnumSet;
import java.util.Set;
import org.jooq.JSONB;

/**
 * What a task needs next, as a pure function of its state: the reactor's rule table for the
 * evidence-gated lifecycle (first match wins).
 *
 * <ol>
 *   <li>terminal, pending, or a run already queued or running → nothing;</li>
 *   <li>the scheduler is paused → nothing;</li>
 *   <li>too many failures while running, verifying or verified → a person;</li>
 *   <li>ready, with a worktree slot → implement;</li>
 *   <li>running → a fix after verify gaps, an integration conflict or a failed fix; otherwise
 *       implement (with review gaps as feedback, or a failed attempt's own feedback);</li>
 *   <li>verifying or verified, and the worktree moved under orange → a person;</li>
 *   <li>verifying → verify, then diff review, then verified;</li>
 *   <li>verified → integrate;</li>
 *   <li>anything else → nothing.</li>
 * </ol>
 */
public final class ReactorDecision {

    private static final Set<TaskStatus> GIVE_UP_FROM = EnumSet.of(TaskStatus.RUNNING, TaskStatus.VERIFYING,
            TaskStatus.VERIFIED);

    private ReactorDecision() {}

    /**
     * @param input the run's own input, for retrying a failed attempt with the same feedback
     */
    public record LastRun(RunKind kind, RunStatus status, JSONB outcome, JSONB input) {}

    /**
     * @param workspaceAvailable the task has a worktree, or there is room for one more
     * @param verifyPassed       a verify run of {@code headSha} succeeded with every check passing
     * @param reviewPassed       {@code headSha} has a passing diff review
     */
    public record TaskSnapshot(TaskStatus status, int failures, boolean hasActiveRun, boolean paused,
                               boolean workspaceAvailable, String headSha, LastRun lastRun, boolean verifyPassed,
                               boolean reviewPassed) {}

    public sealed interface Action {
        /** @param feedback a JSON array of {@code {source, summary, detail}}, or null */
        record QueueImplement(JSONB feedback) implements Action {}

        record QueueFix(JSONB feedback) implements Action {}

        record QueueVerify(String headSha) implements Action {}

        record QueueDiffReview(String headSha) implements Action {}

        record QueueIntegrate(String headSha) implements Action {}

        record MarkVerified() implements Action {}

        record NeedsHuman(String reason) implements Action {}

        record None() implements Action {}
    }

    public static Action decide(TaskSnapshot s, int needsHumanAfter) {
        if (s.status().isTerminal() || s.status() == TaskStatus.PENDING || s.hasActiveRun()) return new Action.None();
        if (s.paused()) return new Action.None();
        if (s.failures() >= needsHumanAfter && GIVE_UP_FROM.contains(s.status())) {
            return new Action.NeedsHuman("failures");
        }
        RunOutcome last = s.lastRun() == null ? null : RunOutcome.parse(s.lastRun().outcome()).orElse(null);
        return switch (s.status()) {
            case READY -> s.workspaceAvailable() ? new Action.QueueImplement(null) : new Action.None();
            case RUNNING -> running(s.lastRun(), last);
            case VERIFYING -> {
                if (worktreeMoved(last)) yield new Action.NeedsHuman("worktree_moved");
                if (s.headSha() == null || s.headSha().isBlank()) yield new Action.None();
                if (!s.verifyPassed()) yield new Action.QueueVerify(s.headSha());
                if (!s.reviewPassed()) yield new Action.QueueDiffReview(s.headSha());
                yield new Action.MarkVerified();
            }
            case VERIFIED -> {
                if (worktreeMoved(last)) yield new Action.NeedsHuman("worktree_moved");
                yield s.headSha() == null || s.headSha().isBlank() ? new Action.None()
                        : new Action.QueueIntegrate(s.headSha());
            }
            default -> new Action.None();
        };
    }

    private static Action running(LastRun lastRun, RunOutcome last) {
        if (lastRun != null) {
            if (last instanceof RunOutcome.Verification v && !v.stale() && !v.passed()) {
                return new Action.QueueFix(verifyFeedback(v));
            }
            if (last instanceof RunOutcome.Integration i && "conflict".equals(i.status())) {
                return new Action.QueueFix(conflictFeedback(i));
            }
            if (lastRun.kind() == RunKind.CI_FIX && lastRun.status() == RunStatus.FAILED) {
                return new Action.QueueFix(ownFeedback(lastRun));
            }
            if (last instanceof RunOutcome.Review r && "gaps".equals(r.verdict())) {
                return new Action.QueueImplement(reviewFeedback(r));
            }
            if (lastRun.kind() == RunKind.IMPLEMENT && lastRun.status() == RunStatus.FAILED) {
                return new Action.QueueImplement(ownFeedback(lastRun));
            }
        }
        return new Action.QueueImplement(null);
    }

    /** The worktree was not at the head orange was verifying or integrating: someone moved it. */
    private static boolean worktreeMoved(RunOutcome last) {
        return (last instanceof RunOutcome.Verification v && v.stale())
                || (last instanceof RunOutcome.Integration i && "stale".equals(i.status()));
    }

    // ─────────────────────────── feedback ───────────────────────────

    private static JSONB verifyFeedback(RunOutcome.Verification v) {
        ArrayNode items = Json.mapper().createArrayNode();
        for (EvidenceGap g : v.gaps()) {
            String summary;
            if (EvidenceRunner.STATUS_COMMAND.equals(g.command())) {
                summary = "Uncommitted or untracked files are left in the worktree: commit or remove them";
            } else if (g.exitCode() != null && g.exitCode() == -1) {
                summary = "`" + g.command() + "` timed out";
            } else {
                summary = "`" + g.command() + "` failed (exit " + g.exitCode() + ")";
            }
            item(items, g.source(), summary, g.outputTail());
        }
        return JSONB.valueOf(items.toString());
    }

    private static JSONB conflictFeedback(RunOutcome.Integration i) {
        ArrayNode items = Json.mapper().createArrayNode();
        item(items, "integration", "Integrating this branch onto the base conflicts in: " + String.join(", ", i.files()),
                "Rebase this branch onto the base branch named in your prompt, resolve the conflicts so that both "
                        + "sides' changes are kept, and commit the result.");
        return JSONB.valueOf(items.toString());
    }

    private static JSONB reviewFeedback(RunOutcome.Review r) {
        ArrayNode items = Json.mapper().createArrayNode();
        for (ReviewGap g : r.gaps()) {
            String where = g.path() == null ? "" : g.path() + (g.line() == null ? "" : ":" + g.line()) + ": ";
            item(items, "diff_review", where + g.body(), null);
        }
        return JSONB.valueOf(items.toString());
    }

    /** A failed attempt's own feedback, so a retry still addresses what it was asked to; null if it had none. */
    private static JSONB ownFeedback(LastRun lastRun) {
        if (lastRun.input() == null) return null;
        try {
            JsonNode feedback = Json.mapper().readTree(lastRun.input().data()).path("feedback");
            return feedback.isArray() && !feedback.isEmpty() ? JSONB.valueOf(feedback.toString()) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void item(ArrayNode items, String source, String summary, String detail) {
        ObjectNode item = items.addObject().put("source", source).put("summary", summary);
        if (detail != null && !detail.isBlank()) item.put("detail", detail);
    }
}
