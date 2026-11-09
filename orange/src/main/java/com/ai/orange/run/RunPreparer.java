package com.ai.orange.run;

import com.ai.orange.json.Json;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.nio.file.Path;
import java.util.List;
import org.jooq.JSONB;
import org.springframework.stereotype.Component;

/**
 * Gets an agent run ready to start: its working directory, its prompt, and what routing needs to
 * know. A failure here is reported as {@code workspace_failed}.
 */
@Component
public class RunPreparer {

    /** Each previous attempt is summarised in at most this many characters. */
    static final int MAX_ATTEMPT_TEXT = 2_000;
    private static final String DEFAULT_DIFFICULTY = "M";

    private final TaskRepository tasks;
    private final RunRepository runs;
    private final RunEventRepository runEvents;
    private final WorktreeService worktrees;

    public RunPreparer(TaskRepository tasks, RunRepository runs, RunEventRepository runEvents,
                       WorktreeService worktrees) {
        this.tasks = tasks;
        this.runs = runs;
        this.runEvents = runEvents;
        this.worktrees = worktrees;
    }

    public PreparedRun prepare(Run run) throws Exception {
        return switch (run.kind()) {
            case IMPLEMENT, CI_FIX -> prepareCoding(run);
            default -> throw new IllegalArgumentException("no preparation for " + run.kind().dbValue() + " runs");
        };
    }

    private PreparedRun prepareCoding(Run run) throws Exception {
        if (run.taskId() == null) throw new IllegalArgumentException("run " + run.id() + " has no task");
        Task task = tasks.findById(run.taskId())
                .orElseThrow(() -> new IllegalStateException("task " + run.taskId() + " does not exist"));
        Path cwd = worktrees.ensure(task.id());
        JsonNode meta = json(task.metadata());
        String difficulty = meta.path("difficulty").asText("");
        return new PreparedRun(cwd, codingPrompt(task, meta, run), run.role(),
                difficulty.isBlank() ? DEFAULT_DIFFICULTY : difficulty, meta.path("risk").asBoolean(false),
                task.failures(), null);
    }

    /**
     * The task, the operator's note from a reopen, the feedback this run exists to address, and
     * how the task's earlier coding attempts ended.
     */
    String codingPrompt(Task task, JsonNode meta, Run run) {
        StringBuilder b = new StringBuilder();
        b.append("# ").append(task.title().strip()).append("\n\n");
        if (task.description() != null && !task.description().isBlank()) {
            b.append(task.description().strip()).append("\n");
        }

        String reopen = meta.path("reopen_reason").path("reason").asText("");
        if (!reopen.isBlank()) {
            b.append("\n## Note from the operator\n\n").append(reopen.strip()).append("\n");
        }

        JsonNode feedback = json(run.input()).path("feedback");
        if (feedback.isArray() && !feedback.isEmpty()) {
            b.append("\n## Feedback to address\n\n");
            int n = 0;
            for (JsonNode item : feedback) {
                b.append(++n).append(". ");
                String source = item.path("source").asText("");
                if (!source.isBlank()) b.append('[').append(source).append("] ");
                b.append(item.path("summary").asText("").strip()).append('\n');
                String detail = item.path("detail").asText("");
                if (!detail.isBlank()) {
                    b.append('\n');
                    detail.strip().lines().forEach(line -> b.append("   ").append(line).append('\n'));
                }
                b.append('\n');
            }
        }

        List<Run> failed = runs.findByTask(task.id()).stream()
                .filter(r -> !r.id().equals(run.id()))
                .filter(r -> r.kind() == RunKind.IMPLEMENT || r.kind() == RunKind.CI_FIX)
                .filter(r -> r.status() == RunStatus.FAILED && FailureReasons.isModelFailure(r.error()))
                .toList();
        if (!failed.isEmpty()) {
            b.append("\n## Previous attempts\n\n");
            for (Run r : failed) {
                b.append("- Attempt ").append(r.attempt()).append(" (").append(r.kind().dbValue()).append(") ended with ")
                        .append(r.error()).append(": ").append(truncate(attemptText(r))).append('\n');
            }
        }
        return b.toString();
    }

    private String attemptText(Run r) {
        String summary = RunOutcome.parse(r.outcome())
                .map(o -> o instanceof RunOutcome.Result res ? res.summary() : ((RunOutcome.Review) o).summary())
                .orElse(null);
        if (summary != null && !summary.isBlank()) return summary.strip();
        return runEvents.findLastByType(r.id(), "assistant")
                .map(e -> json(e.payload()).path("text").asText(""))
                .filter(t -> !t.isBlank())
                .map(String::strip)
                .orElse("(no message)");
    }

    private static String truncate(String text) {
        String oneLine = text.replace('\n', ' ');
        return oneLine.length() <= MAX_ATTEMPT_TEXT ? oneLine : oneLine.substring(0, MAX_ATTEMPT_TEXT - 1) + "…";
    }

    private static JsonNode json(JSONB value) {
        if (value == null) return MissingNode.getInstance();
        try {
            return Json.mapper().readTree(value.data());
        } catch (Exception e) {
            return MissingNode.getInstance();
        }
    }
}
