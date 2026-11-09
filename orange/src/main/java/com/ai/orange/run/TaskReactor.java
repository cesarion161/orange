package com.ai.orange.run;

import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.json.Json;
import com.ai.orange.listener.PgNotificationEvent;
import com.ai.orange.routing.RoutingProperties;
import com.ai.orange.run.ReactorDecision.Action;
import com.ai.orange.run.ReactorDecision.LastRun;
import com.ai.orange.run.ReactorDecision.TaskSnapshot;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.util.Optional;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves each task along the evidence-gated lifecycle by queueing its next run. It reacts to
 * {@code task_changed} and {@code run_changed}, and sweeps every active task as a backstop.
 * Each reaction locks the task's row, so concurrent reactions decide one at a time and the
 * one-active-run index catches anything else.
 */
@Component
public class TaskReactor {

    private static final Logger log = LoggerFactory.getLogger(TaskReactor.class);
    static final String ACTOR = "orange";

    private final TaskRepository tasks;
    private final RunRepository runs;
    private final EvidenceRepository evidence;
    private final TaskEventRepository taskEvents;
    private final SchedulerPause pause;
    private final WorktreeService worktrees;
    private final RoutingProperties routing;
    private final RunsProperties props;
    private final TransactionTemplate tx;

    public TaskReactor(TaskRepository tasks, RunRepository runs, EvidenceRepository evidence,
                       TaskEventRepository taskEvents, SchedulerPause pause, WorktreeService worktrees,
                       RoutingProperties routing, RunsProperties props, PlatformTransactionManager txManager) {
        this.tasks = tasks;
        this.runs = runs;
        this.evidence = evidence;
        this.taskEvents = taskEvents;
        this.pause = pause;
        this.worktrees = worktrees;
        this.routing = routing;
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    /** Decides and does the task's next step, in one transaction holding the task's row lock. */
    public void react(UUID taskId) {
        tx.executeWithoutResult(status -> tasks.lockForUpdate(taskId).ifPresent(task -> {
            Action action = ReactorDecision.decide(snapshot(task), needsHumanAfter());
            if (!(action instanceof Action.None)) log.info("task {} ({}): {}", task.id(), task.status().dbValue(), action);
            act(task, action);
        }));
    }

    /** Reacts to every task that may have a next step. */
    public void sweep() {
        for (UUID id : tasks.findActiveIds()) safeReact(id);
    }

    @Scheduled(fixedDelayString = "${orange.runs.reactor-sweep:30s}", initialDelayString = "${orange.runs.reactor-sweep:30s}")
    void scheduledSweep() {
        if (props.loopEnabled()) sweep();
    }

    @EventListener
    void onNotification(PgNotificationEvent event) {
        if (!props.loopEnabled()) return;
        if (!"task_changed".equals(event.channel()) && !"run_changed".equals(event.channel())) return;
        if (event.payload() == null || event.payload().isBlank()) return;
        UUID taskId;
        try {
            taskId = UUID.fromString(event.payload().strip());
        } catch (IllegalArgumentException e) {
            return;
        }
        // Off the listener thread: a reaction takes a lock and may wait for it.
        Thread.ofVirtual().name("react-" + taskId).start(() -> safeReact(taskId));
    }

    private void safeReact(UUID taskId) {
        try {
            react(taskId);
        } catch (RuntimeException e) {
            log.warn("reacting to task {} failed: {}", taskId, e.toString());
        }
    }

    TaskSnapshot snapshot(Task t) {
        boolean active = runs.findActiveByTask(t.id()).isPresent();
        boolean paused = pause.current().isPresent();
        // Implement and fix runs need the task's worktree: it exists, or there is a slot for it.
        boolean needsWorktree = t.status() == TaskStatus.READY || t.status() == TaskStatus.RUNNING;
        boolean workspace = !needsWorktree || Files.exists(worktrees.resolve(t.id())) || worktrees.hasCapacity();
        LastRun last = runs.findLastFinished(t.id())
                .map(r -> new LastRun(r.kind(), r.status(), r.outcome(), r.input()))
                .orElse(null);
        String head = t.headSha();
        boolean verifyPassed = head != null && runs.hasSucceeded(t.id(), RunKind.VERIFY, head);
        boolean reviewPassed = head != null && evidence.hasPassingDiffReview(t.id(), head);
        return new TaskSnapshot(t.status(), t.failures(), active, paused, workspace, head, last, verifyPassed,
                reviewPassed);
    }

    private void act(Task task, Action action) {
        switch (action) {
            case Action.QueueImplement a -> {
                Optional<Run> run = queue(task, RunKind.IMPLEMENT, task.role(), feedbackInput(a.feedback()));
                if (run.isPresent() && task.status() == TaskStatus.READY) {
                    tasks.transitionStatus(task.id(), TaskStatus.READY, TaskStatus.RUNNING);
                }
            }
            case Action.QueueFix a -> queue(task, RunKind.CI_FIX, "ci_fixer", feedbackInput(a.feedback()));
            case Action.QueueVerify a -> queue(task, RunKind.VERIFY, ACTOR, headInput(a.headSha()));
            case Action.QueueDiffReview a -> queue(task, RunKind.DIFF_REVIEW, "reviewer", headInput(a.headSha()));
            case Action.QueueIntegrate a -> queue(task, RunKind.INTEGRATE, ACTOR, headInput(a.headSha()));
            case Action.MarkVerified a -> {
                if (tasks.transitionStatus(task.id(), TaskStatus.VERIFYING, TaskStatus.VERIFIED)) {
                    event(task, "verified", Json.mapper().createObjectNode().put("head_sha", task.headSha()));
                }
            }
            case Action.NeedsHuman a -> {
                if (tasks.transitionStatus(task.id(), task.status(), TaskStatus.NEEDS_HUMAN)) {
                    event(task, "needs_human", Json.mapper().createObjectNode().put("reason", a.reason()));
                }
            }
            case Action.None a -> { }
        }
    }

    private Optional<Run> queue(Task task, RunKind kind, String role, JSONB input) {
        Optional<Run> run = runs.queue(task.id(), kind, role, task.priority(), input);
        run.ifPresent(r -> event(task, "run_queued", Json.mapper().createObjectNode()
                .put("run_id", r.id().toString()).put("kind", kind.dbValue())));
        return run;
    }

    private int needsHumanAfter() {
        return routing.escalation() == null ? Integer.MAX_VALUE : routing.escalation().needsHumanAfterFailures();
    }

    private static JSONB feedbackInput(JSONB feedback) {
        if (feedback == null) return JSONB.valueOf("{}");
        try {
            ObjectNode input = Json.mapper().createObjectNode();
            input.set("feedback", Json.mapper().readTree(feedback.data()));
            return JSONB.valueOf(input.toString());
        } catch (Exception e) {
            return JSONB.valueOf("{}");
        }
    }

    private static JSONB headInput(String headSha) {
        return JSONB.valueOf(Json.mapper().createObjectNode().put("head_sha", headSha).toString());
    }

    private void event(Task task, String type, ObjectNode payload) {
        taskEvents.append(task.id(), ACTOR, type, JSONB.valueOf(payload.toString()));
    }
}
