package com.ai.orange.run;

import com.ai.orange.json.Json;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.session.JudgePrompt;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionRepository;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jooq.JSONB;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Gets an agent run ready to start: its working directory, its prompt, and what routing needs to
 * know. A failure here is reported as {@code workspace_failed}.
 */
@Component
public class RunPreparer {

    /** Each previous attempt is summarised in at most this many characters. */
    static final int MAX_ATTEMPT_TEXT = 2_000;
    /** A diff review's prompt is cut here (with a note saying where). */
    static final int MAX_REVIEW_PROMPT = 200_000;
    private static final String DEFAULT_DIFFICULTY = "M";

    private final TaskRepository tasks;
    private final RunRepository runs;
    private final RunEventRepository runEvents;
    private final WorktreeService worktrees;
    private final JudgePrompt judgePrompt;
    private final SessionRepository sessions;
    private final HarnessProperties harness;

    public RunPreparer(TaskRepository tasks, RunRepository runs, RunEventRepository runEvents,
                       WorktreeService worktrees, @Lazy JudgePrompt judgePrompt, SessionRepository sessions,
                       HarnessProperties harness) {
        this.sessions = sessions;
        this.harness = harness;
        this.judgePrompt = judgePrompt;
        this.tasks = tasks;
        this.runs = runs;
        this.runEvents = runEvents;
        this.worktrees = worktrees;
    }

    public PreparedRun prepare(Run run) throws Exception {
        return switch (run.kind()) {
            case IMPLEMENT, CI_FIX -> prepareCoding(run);
            case DIFF_REVIEW -> prepareReview(run);
            case JUDGE -> judgePrompt.prepare(run);
            default -> throw new IllegalArgumentException("no preparation for " + run.kind().dbValue() + " runs");
        };
    }

    private PreparedRun prepareCoding(Run run) throws Exception {
        Task task = task(run);
        Path cwd = worktrees.ensure(task.id());
        JsonNode meta = json(task.metadata());
        return prepared(run, task, meta, cwd, codingPrompt(task, meta, run, worktrees.baseRef()));
    }

    /**
     * The reviewer works in the task's worktree with read-only tools (no code-changing run is
     * active while a task verifies), and its prompt carries the whole diff, since it has no shell.
     */
    private PreparedRun prepareReview(Run run) throws Exception {
        if (run.attendedSessionId() != null) return prepareSessionReview(run);
        Task task = task(run);
        Path cwd = worktrees.ensure(task.id());
        String head = EvidenceRunner.inputHead(run);
        if (head.isBlank()) head = task.headSha();
        if (head == null || head.isBlank()) throw new IllegalStateException("task " + task.id() + " has no head to review");
        String baseRef = worktrees.baseRef();
        String range = baseRef + "..." + head;
        String stat = Git.run(cwd, Git.QUICK, "diff", "--stat", range);
        Git.Output diff = Git.runCapped(cwd, Git.QUICK, MAX_REVIEW_PROMPT + 1, "diff", range);
        JsonNode meta = json(task.metadata());
        return prepared(run, task, meta, cwd, reviewPrompt(task, baseRef, head, stat, diff));
    }

    /**
     * The review of a session's work. Like the judge it runs in an empty directory of its own with
     * no file tools: the diff is text a session wrote, and starting Claude Code inside the user's
     * checkout would load that repo's settings and hooks.
     */
    private PreparedRun prepareSessionReview(Run run) throws Exception {
        Session s = sessions.findById(run.attendedSessionId())
                .orElseThrow(() -> new IllegalStateException("session " + run.attendedSessionId() + " does not exist"));
        JsonNode in = json(run.input());
        String head = in.path("head_sha").asText("");
        String base = in.path("base_sha").asText("");
        String where = s.worktree() != null ? s.worktree() : s.repoRoot();
        if (head.isBlank() || base.isBlank() || where == null) {
            throw new IllegalStateException("session " + s.id() + " has no diff to review");
        }
        Path repo = Path.of(where);
        String stat = Git.run(repo, Git.QUICK, "diff", "--stat", base, head);
        Git.Output diff = Git.runCapped(repo, Git.QUICK, MAX_REVIEW_PROMPT + 1, "diff", base, head);
        String intent = s.intent() == null ? "(the session stated no intent)" : s.intent().strip();
        String prompt = reviewPrompt("Work of an interactive session", "What the user asked the session for:\n\n"
                + intent + "\n\nYou have no file tools here: judge from the diff below alone.", base, head, stat, diff,
                "The rest of the diff is not shown: review what is shown and say in your summary that it was cut.");
        Path cwd = Files.createDirectories(harness.runRoot().resolve(run.id().toString()).resolve("empty"));
        return new PreparedRun(cwd, prompt, run.role(), DEFAULT_DIFFICULTY, false, 0, null);
    }

    private Task task(Run run) {
        if (run.taskId() == null) throw new IllegalArgumentException("run " + run.id() + " has no task");
        return tasks.findById(run.taskId())
                .orElseThrow(() -> new IllegalStateException("task " + run.taskId() + " does not exist"));
    }

    private static PreparedRun prepared(Run run, Task task, JsonNode meta, Path cwd, String prompt) {
        String difficulty = meta.path("difficulty").asText("");
        return new PreparedRun(cwd, prompt, run.role(), difficulty.isBlank() ? DEFAULT_DIFFICULTY : difficulty,
                meta.path("risk").asBoolean(false), task.failures(), null);
    }

    /** The intent, the range under review, its stat and its diff; cut at {@link #MAX_REVIEW_PROMPT}. */
    String reviewPrompt(Task task, String baseRef, String head, String stat, Git.Output diff) {
        return reviewPrompt(task.title(), task.description(), baseRef, head, stat, diff,
                "The rest of the diff is not shown: read the changed files listed above in the worktree.");
    }

    String reviewPrompt(String title, String description, String baseRef, String head, String stat, Git.Output diff,
                        String whenCut) {
        StringBuilder b = new StringBuilder();
        b.append("# ").append(title.strip()).append("\n\n");
        if (description != null && !description.isBlank()) {
            b.append(description.strip()).append("\n\n");
        }
        b.append("## Change under review\n\n")
                .append("Base: `").append(baseRef).append("`\n")
                .append("Head: `").append(head).append("`\n\n")
                .append("```\n").append(stat.strip()).append("\n```\n\n")
                .append("## Diff\n\n```diff\n").append(diff.text());
        if (!diff.text().endsWith("\n")) b.append('\n');
        b.append("```\n");
        if (b.length() <= MAX_REVIEW_PROMPT && !diff.truncated()) return b.toString();

        String cut = b.substring(0, Math.min(b.length(), MAX_REVIEW_PROMPT));
        int file = cut.lastIndexOf("\ndiff --git a/");
        String where = "";
        if (file >= 0) {
            int end = cut.indexOf(" b/", file);
            if (end > file) where = ", inside the diff of `" + cut.substring(file + "\ndiff --git a/".length(), end) + "`";
        }
        return cut + "\n```\n\n[orange: the prompt was cut here, at " + MAX_REVIEW_PROMPT + " characters" + where
                + ". " + whenCut + "]\n";
    }

    /**
     * The task, the operator's note from a reopen, the feedback this run exists to address, and
     * how the task's earlier coding attempts ended.
     */
    String codingPrompt(Task task, JsonNode meta, Run run, String baseRef) {
        StringBuilder b = new StringBuilder();
        b.append("# ").append(task.title().strip()).append("\n\n");
        if (task.description() != null && !task.description().isBlank()) {
            b.append(task.description().strip()).append("\n");
        }
        b.append("\nYou are on branch `orange/").append(task.id()).append("`; orange integrates it onto `")
                .append(baseRef).append("`.\n");

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
                .map(o -> switch (o) {
                    case RunOutcome.Result res -> res.summary();
                    case RunOutcome.Review rev -> rev.summary();
                    default -> null;
                })
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
