package com.ai.orange.run;

import com.ai.orange.json.Json;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import java.time.ZonedDateTime;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * What a finished run does to its task and to the scheduler. It runs while the run row is still
 * {@code running}, so the reactor cannot queue the task's next run before these effects land.
 */
@Component
public class PostRunSteps {

    private static final Logger log = LoggerFactory.getLogger(PostRunSteps.class);
    static final String ACTOR = "orange";

    private final TaskRepository tasks;
    private final TaskEventRepository taskEvents;
    private final SchedulerPause pause;

    public PostRunSteps(TaskRepository tasks, TaskEventRepository taskEvents, SchedulerPause pause) {
        this.tasks = tasks;
        this.taskEvents = taskEvents;
        this.pause = pause;
    }

    /** Applies the run's effects and returns its final result. */
    public RunResult after(Run run, RunResult result) {
        if (run.taskId() != null) {
            if (FailureReasons.isModelFailure(result.reason())) {
                tasks.incrementFailures(run.taskId());
            }
            if (result.outcome() instanceof RunOutcome.Result r && "blocked".equals(r.status())) {
                needsHuman(run, r.question() != null ? r.question() : r.summary());
            }
        }
        String text = String.join("\n", nullToEmpty(result.resultText()), nullToEmpty(result.lastAssistantText()));
        FailureReasons.pauseFor(result.reason(), text, ZonedDateTime.now()).ifPresent(p -> {
            log.warn("run {} failed with {}: pausing the scheduler {}", run.id(), p.reason(),
                    p.until() == null ? "until resumed" : "until " + p.until());
            pause.pause(p.reason(), p.until());
        });
        return result;
    }

    private void needsHuman(Run run, String question) {
        Task task = tasks.findById(run.taskId()).orElse(null);
        if (task == null || task.status().isTerminal() || task.status() == TaskStatus.NEEDS_HUMAN) return;
        if (!tasks.transitionStatus(task.id(), task.status(), TaskStatus.NEEDS_HUMAN)) {
            log.info("task {} moved on before run {}'s question landed", task.id(), run.id());
            return;
        }
        taskEvents.append(task.id(), ACTOR, "needs_human", JSONB.valueOf(Json.mapper().createObjectNode()
                .put("run_id", run.id().toString())
                .put("question", question)
                .toString()));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
