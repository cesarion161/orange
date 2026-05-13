package com.ai.orange.github;

import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Composes the moving parts of "open a PR for this task": pushes the task's
 * worktree branch to the remote, calls {@link GithubClient} to open the PR,
 * and persists the resulting metadata on the task row.
 */
@Service
public class GithubPrService {

    private static final Logger log = LoggerFactory.getLogger(GithubPrService.class);
    public static final String CORRELATION_PREFIX = "<!-- orange-task: ";
    public static final String CORRELATION_SUFFIX = " -->";

    private final GithubClient client;
    private final GithubProperties props;
    private final TaskRepository tasks;
    private final TaskService taskService;
    private final ObjectMapper mapper = new ObjectMapper();

    public GithubPrService(GithubClient client, GithubProperties props,
                           TaskRepository tasks, TaskService taskService) {
        this.client = client;
        this.props = props;
        this.tasks = tasks;
        this.taskService = taskService;
    }

    /**
     * Push the worktree's branch and open a PR. Returns the PR info; also
     * stamps {@code pr_number}, {@code pr_url}, and {@code pr_repo} into
     * {@code tasks.metadata} so webhook routing can find the task by PR.
     */
    public PrInfo openPrForTask(UUID taskId, Path worktreePath) throws IOException, InterruptedException {
        Task task = tasks.findById(taskId)
                .orElseThrow(() -> new IllegalStateException("task " + taskId + " not found"));
        String branch = "orange/" + taskId;

        try {
            pushBranch(worktreePath, branch);
        } catch (PushConflictException conflict) {
            // The branch's parent has moved on main. The dev agent's worktree is
            // now diverged; reopening the dev task with the conflict log gives
            // a triage agent a chance to decide who fixes it. We DO bubble the
            // failure so the workflow marks the stage failed — the triage task
            // is a side effect that lets the orchestrator self-heal.
            enqueueConflictTriage(taskId, branch, conflict.output());
            throw conflict;
        }

        String title = task.title();
        String body = task.description() == null ? "" : task.description();
        body = body + "\n\n" + CORRELATION_PREFIX + taskId + CORRELATION_SUFFIX;

        PrInfo pr = client.openPullRequest(title, body, branch, props.baseBranch(), props.defaultReviewers());

        persistPrMetadata(task, pr);
        return pr;
    }

    private void enqueueConflictTriage(UUID taskId, String branch, String pushOutput) {
        try {
            String description = "Git push for branch `" + branch + "` was rejected by the remote — "
                    + "likely a non-fast-forward / merge conflict against the base branch.\n\n"
                    + "Suspect task: " + taskId + "\n"
                    + "Push output:\n" + tail(pushOutput);
            taskService.createTriageTask("conflict", List.of(taskId), description);
            log.info("conflict-triage task enqueued for {}", taskId);
        } catch (Exception e) {
            log.warn("could not enqueue conflict-triage task for {}: {}", taskId, e.getMessage());
        }
    }

    private static String tail(String s) {
        if (s == null) return "";
        int max = 2048;
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private void pushBranch(Path worktreePath, String branch) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("git", "push", "-u", "origin", branch)
                .directory(worktreePath.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) out.append(line).append('\n');
        }
        if (!proc.waitFor(60, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("git push timed out");
        }
        if (proc.exitValue() != 0) {
            String output = out.toString();
            if (isConflictRejection(output)) {
                throw new PushConflictException(proc.exitValue(), output);
            }
            throw new IOException("git push failed (exit " + proc.exitValue() + "):\n" + output);
        }
    }

    /**
     * Heuristic for the "remote rejected this push as non-fast-forward" case.
     * git emits a handful of phrasings depending on remote / version; we look
     * for the most stable ones.
     */
    private static boolean isConflictRejection(String pushOutput) {
        if (pushOutput == null) return false;
        String lower = pushOutput.toLowerCase();
        return lower.contains("non-fast-forward")
                || lower.contains("updates were rejected")
                || lower.contains("rejected")
                && (lower.contains("fetch first") || lower.contains("pull first"));
    }

    /**
     * Thrown when {@code git push} fails specifically because the remote tip
     * has moved (non-fast-forward). Distinct from generic {@link IOException}
     * so callers can branch on triage-relevant failures.
     */
    public static class PushConflictException extends IOException {
        private final String output;
        public PushConflictException(int exitCode, String output) {
            super("git push rejected as non-fast-forward (exit " + exitCode + ")");
            this.output = output;
        }
        public String output() { return output; }
    }

    private void persistPrMetadata(Task task, PrInfo pr) {
        try {
            ObjectNode node = (task.metadata() == null)
                    ? mapper.createObjectNode()
                    : (ObjectNode) mapper.readTree(task.metadata().data());
            node.put("pr_number", pr.number());
            node.put("pr_url", pr.htmlUrl());
            node.put("pr_repo", pr.repo());
            tasks.updateMetadata(task.id(), JSONB.valueOf(mapper.writeValueAsString(node)));
        } catch (Exception e) {
            log.warn("could not stamp PR metadata onto task {}: {}", task.id(), e.getMessage());
        }
    }

    /**
     * Pulls a task UUID out of a PR body that contains {@code <!-- orange-task: <uuid> --> }.
     * Used by {@code GithubEventRouter} as a fallback when the metadata-based
     * lookup misses (e.g. PR created out-of-band).
     */
    public static Optional<UUID> extractTaskIdFromBody(String body) {
        if (body == null) return Optional.empty();
        int start = body.indexOf(CORRELATION_PREFIX);
        if (start < 0) return Optional.empty();
        int valueStart = start + CORRELATION_PREFIX.length();
        int end = body.indexOf(CORRELATION_SUFFIX, valueStart);
        if (end < 0) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(body.substring(valueStart, end).trim()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

}
