package com.ai.orange.run;

import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.json.Json;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.stream.Collectors;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What a finished run does to its task and to the scheduler. It runs while the run row is still
 * {@code running}, so the reactor cannot queue the task's next run before these effects land.
 *
 * <ul>
 *   <li>implement/ci_fix reporting {@code done}: with commits, the head is recorded and the task
 *       moves to {@code verifying}; without, the run fails as {@code no_commits};</li>
 *   <li>verify: gaps send the task back to {@code running} and count;</li>
 *   <li>diff_review: the verdict is stored as evidence; {@code pass} verifies the task, gaps send
 *       it back and count;</li>
 *   <li>integrate: integrated, rebased (re-verify), conflict (back for a fix), or a person;</li>
 *   <li>any run: model failures count, a {@code blocked} report asks a person, infra failures
 *       pause the scheduler.</li>
 * </ul>
 */
@Component
public class PostRunSteps {

    private static final Logger log = LoggerFactory.getLogger(PostRunSteps.class);
    static final String ACTOR = "orange";

    private final TaskRepository tasks;
    private final TaskEventRepository taskEvents;
    private final SchedulerPause pause;
    private final WorktreeService worktrees;
    private final EvidenceRepository evidence;

    public PostRunSteps(TaskRepository tasks, TaskEventRepository taskEvents, SchedulerPause pause,
                        WorktreeService worktrees, EvidenceRepository evidence) {
        this.tasks = tasks;
        this.taskEvents = taskEvents;
        this.pause = pause;
        this.worktrees = worktrees;
        this.evidence = evidence;
    }

    /** Applies the run's effects and returns its final result. */
    public RunResult after(Run run, RunResult result) {
        RunResult r = result;
        if (run.taskId() != null) {
            r = switch (run.kind()) {
                case IMPLEMENT, CI_FIX -> afterCoding(run, r);
                case VERIFY -> afterVerify(run, r);
                case DIFF_REVIEW -> afterReview(run, r);
                case INTEGRATE -> afterIntegrate(run, r);
                default -> r;
            };
            if (FailureReasons.isModelFailure(r.reason())) {
                tasks.incrementFailures(run.taskId());
            }
            if (r.outcome() instanceof RunOutcome.Result res && "blocked".equals(res.status())) {
                needsHuman(run, res.question() != null ? res.question() : res.summary());
            }
        }
        String text = String.join("\n", nullToEmpty(r.resultText()), nullToEmpty(r.lastAssistantText()));
        FailureReasons.pauseFor(r.reason(), text, ZonedDateTime.now()).ifPresent(p -> {
            log.warn("run {} failed with {}: pausing the scheduler {}", run.id(), p.reason(),
                    p.until() == null ? "until resumed" : "until " + p.until());
            pause.pause(p.reason(), p.until());
        });
        return r;
    }

    // ─────────────────────────── per kind ───────────────────────────

    /** {@code done} hands the work to verification, but only if there is work: commits on the branch. */
    private RunResult afterCoding(Run run, RunResult r) {
        if (r.reason() != null || !(r.outcome() instanceof RunOutcome.Result res) || !"done".equals(res.status())) {
            return r;
        }
        String head;
        try {
            Path wt = worktrees.resolve(run.taskId());
            if (Git.commitsAhead(wt, worktrees.baseRef()) == 0) return r.withFailure("no_commits");
            head = Git.head(wt);
        } catch (RuntimeException e) {
            log.error("run {}: cannot read the task's branch: {}", run.id(), e.getMessage());
            return r.withFailure("workspace_failed");
        }
        tasks.recordHead(run.taskId(), "orange/" + run.taskId(), head);
        if (move(run, TaskStatus.RUNNING, TaskStatus.VERIFYING)) {
            event(run, "verification_started", node(run).put("head_sha", head));
        }
        return r;
    }

    private RunResult afterVerify(Run run, RunResult r) {
        if (!(r.outcome() instanceof RunOutcome.Verification v) || v.stale() || v.passed()) return r;
        if (move(run, TaskStatus.VERIFYING, TaskStatus.RUNNING)) {
            tasks.incrementFailures(run.taskId());
            ObjectNode payload = node(run).put("head_sha", EvidenceRunner.inputHead(run));
            ArrayNode gaps = payload.putArray("gaps");
            for (EvidenceGap g : v.gaps()) {
                ObjectNode gap = gaps.addObject().put("source", g.source()).put("command", g.command())
                        .put("output_tail", g.outputTail());
                if (g.exitCode() == null) gap.putNull("exit_code");
                else gap.put("exit_code", g.exitCode());
            }
            event(run, "verification_failed", payload);
        }
        return r;
    }

    private RunResult afterReview(Run run, RunResult r) {
        // A review that failed (no verdict) counts as a model failure and leaves the task verifying.
        if (r.reason() != null || !(r.outcome() instanceof RunOutcome.Review review)) return r;
        String head = headOf(run);
        boolean pass = "pass".equals(review.verdict());
        evidence.record(run.taskId(), run.id(), head, EvidenceRepository.DIFF_REVIEW, null, null, reviewText(review), pass);
        if (pass) {
            if (move(run, TaskStatus.VERIFYING, TaskStatus.VERIFIED)) {
                event(run, "verified", node(run).put("head_sha", head));
            }
        } else if (move(run, TaskStatus.VERIFYING, TaskStatus.RUNNING)) {
            tasks.incrementFailures(run.taskId());
            ObjectNode payload = node(run).put("head_sha", head).put("summary", review.summary());
            ArrayNode gaps = payload.putArray("gaps");
            for (ReviewGap g : review.gaps()) {
                ObjectNode gap = gaps.addObject().put("body", g.body());
                if (g.path() != null) gap.put("path", g.path());
                if (g.line() != null) gap.put("line", g.line());
            }
            event(run, "review_gaps", payload);
        }
        return r;
    }

    private RunResult afterIntegrate(Run run, RunResult r) {
        if ("integrate_failed".equals(r.reason())) {
            String error = r.outcome() instanceof RunOutcome.Integration i && i.error() != null ? i.error() : "unknown";
            needsHuman(run, "Integration failed: " + error);
            return r;
        }
        if (r.reason() != null || !(r.outcome() instanceof RunOutcome.Integration outcome)) return r;
        switch (outcome.status()) {
            case "integrated" -> {
                // The V14 trigger promotes the task's dependents once it is integrated.
                if (move(run, TaskStatus.VERIFIED, TaskStatus.INTEGRATED)) {
                    event(run, "integrated", node(run).put("head_sha", outcome.headSha())
                            .put("pushed", Boolean.TRUE.equals(outcome.pushed()))
                            .put("local_base", outcome.localBase()));
                }
            }
            case "rebased" -> {
                Task task = tasks.findById(run.taskId()).orElse(null);
                String from = task == null ? EvidenceRunner.inputHead(run) : task.headSha();
                tasks.recordHead(run.taskId(), "orange/" + run.taskId(), outcome.headSha());
                carryOverReview(run, from, outcome.headSha());
                if (move(run, TaskStatus.VERIFIED, TaskStatus.VERIFYING)) {
                    event(run, "rebased", node(run).put("from", from).put("to", outcome.headSha()));
                }
            }
            case "conflict" -> {
                if (move(run, TaskStatus.VERIFIED, TaskStatus.RUNNING)) {
                    ObjectNode payload = node(run);
                    ArrayNode files = payload.putArray("files");
                    outcome.files().forEach(files::add);
                    event(run, "integration_conflict", payload);
                }
            }
            default -> {
                // base_moved, stale: nothing changes; the reactor queues another integration.
            }
        }
        return r;
    }

    /**
     * A clean rebase leaves the task's own patch unchanged, so its passing diff review holds for
     * the new head; only the command evidence has to run again.
     */
    private void carryOverReview(Run run, String from, String to) {
        if (from == null || !evidence.hasPassingDiffReview(run.taskId(), from)) return;
        evidence.record(run.taskId(), run.id(), to, EvidenceRepository.DIFF_REVIEW, null, null,
                "carried over from " + from + " after a clean rebase", true);
    }

    // ─────────────────────────── helpers ───────────────────────────

    private void needsHuman(Run run, String question) {
        Task task = tasks.findById(run.taskId()).orElse(null);
        if (task == null || task.status().isTerminal() || task.status() == TaskStatus.NEEDS_HUMAN) return;
        if (!tasks.transitionStatus(task.id(), task.status(), TaskStatus.NEEDS_HUMAN)) {
            log.info("task {} moved on before run {}'s question landed", task.id(), run.id());
            return;
        }
        event(run, "needs_human", node(run).put("question", question));
    }

    /** Moves the task if it is still where this run left it; false (and nothing done) otherwise. */
    private boolean move(Run run, TaskStatus from, TaskStatus to) {
        boolean moved = tasks.transitionStatus(run.taskId(), from, to);
        if (!moved) {
            log.info("run {}: task {} is no longer {}; not moving it to {}", run.id(), run.taskId(),
                    from.dbValue(), to.dbValue());
        }
        return moved;
    }

    private String headOf(Run run) {
        String head = EvidenceRunner.inputHead(run);
        if (!head.isBlank()) return head;
        return tasks.findById(run.taskId()).map(Task::headSha).orElse("");
    }

    private static String reviewText(RunOutcome.Review review) {
        if (review.gaps().isEmpty()) return review.summary();
        return review.summary() + "\n" + review.gaps().stream().map(PostRunSteps::gapLine)
                .collect(Collectors.joining("\n"));
    }

    private static String gapLine(ReviewGap g) {
        String where = g.path() == null ? "" : g.path() + (g.line() == null ? "" : ":" + g.line()) + ": ";
        return "- " + where + g.body();
    }

    private static ObjectNode node(Run run) {
        return Json.mapper().createObjectNode().put("run_id", run.id().toString());
    }

    private void event(Run run, String type, ObjectNode payload) {
        try {
            taskEvents.append(run.taskId(), ACTOR, type, JSONB.valueOf(payload.toString()));
        } catch (RuntimeException e) {
            log.warn("could not persist {} for task {}: {}", type, run.taskId(), e.getMessage());
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
