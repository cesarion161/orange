package com.ai.orange.postmerge;

import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.worktree.WorktreeProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * After a PR is merged, run a configured build command against the freshly-
 * fetched {@code main} branch. On success: stamp {@code metadata.main_verified_at}
 * onto the task; on failure: append a {@code build_broken} event so the user
 * can spot the regression in {@code orange_tail} / {@code orange_status_live}.
 *
 * <p>Invoked async from {@link com.ai.orange.github.GithubEventRouter} after the
 * {@code PR_OPEN → DEV_READY} transition so the webhook returns fast.
 *
 * <p>Build command and timeout come from configuration:
 * <pre>
 * orange.post-merge.build-command: "./gradlew build"
 * orange.post-merge.timeout: 10m
 * </pre>
 * A blank command (the default) makes the verifier a no-op — keeps the
 * orchestrator usable without forcing every project to wire a build cmd.
 */
@Service
public class PostMergeVerifier {

    private static final Logger log = LoggerFactory.getLogger(PostMergeVerifier.class);

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final TaskService taskService;
    private final WorktreeProperties worktreeProps;
    private final ObjectMapper json = new ObjectMapper();
    private final String buildCommand;
    private final String regressionCommand;
    private final int regressionEvery;
    private final Duration timeout;
    private final boolean triageOnFail;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public PostMergeVerifier(TaskRepository tasks,
                             TaskEventRepository events,
                             TaskService taskService,
                             WorktreeProperties worktreeProps,
                             org.springframework.jdbc.core.JdbcTemplate jdbc,
                             @Value("${orange.post-merge.build-command:}") String buildCommand,
                             @Value("${orange.post-merge.regression-command:}") String regressionCommand,
                             @Value("${orange.post-merge.regression-every:5}") int regressionEvery,
                             @Value("${orange.post-merge.timeout:10m}") Duration timeout,
                             @Value("${orange.post-merge.triage-on-fail:true}") boolean triageOnFail) {
        this.tasks = tasks;
        this.events = events;
        this.taskService = taskService;
        this.worktreeProps = worktreeProps;
        this.jdbc = jdbc;
        this.buildCommand = buildCommand;
        this.regressionCommand = regressionCommand;
        this.regressionEvery = Math.max(regressionEvery, 1);
        this.timeout = timeout;
        this.triageOnFail = triageOnFail;
    }


    /** Production entrypoint: sync origin/main, then run the build. */
    public Result verifyAfterMerge(UUID taskId) {
        return verifyAfterMerge(taskId, true);
    }

    /**
     * Internal/test entrypoint. {@code syncFromOrigin=false} skips the
     * git fetch+checkout+reset and just runs the build in the configured
     * {@code base-repo} directory — useful for tests that don't want to
     * set up a real remote.
     */
    public Result verifyAfterMerge(UUID taskId, boolean syncFromOrigin) {
        if (buildCommand == null || buildCommand.isBlank()) {
            log.debug("post-merge verify skipped for task {} (no build-command configured)", taskId);
            return Result.SKIPPED;
        }
        String baseRepo = worktreeProps.baseRepo();
        if (baseRepo == null || baseRepo.isBlank()) {
            log.warn("post-merge verify for task {}: orange.worktrees.base-repo not set; skipping", taskId);
            return Result.SKIPPED;
        }
        Path repoPath = Path.of(baseRepo).toAbsolutePath();

        if (syncFromOrigin) {
            try {
                run(repoPath, List.of("git", "fetch", "origin", "main"));
                run(repoPath, List.of("git", "checkout", "main"));
                run(repoPath, List.of("git", "reset", "--hard", "origin/main"));
            } catch (Exception e) {
                recordFailure(taskId, "git_sync_failed", e.getMessage());
                return Result.FAILED;
            }
        }

        BuildOutcome outcome;
        try {
            outcome = runBuild(repoPath);
        } catch (Exception e) {
            recordFailure(taskId, "build_invocation_failed", e.getMessage());
            return Result.FAILED;
        }

        if (outcome.exitCode() == 0) {
            stampVerified(taskId);
            events.append(taskId, "orchestrator", "main_build_ok",
                    asJsonb(Map.of("exit_code", 0, "command", buildCommand)));
            log.info("post-merge build passed for task {}", taskId);
            maybeRunRegression(taskId, repoPath);
            return Result.PASSED;
        }
        recordFailure(taskId, "build_failed", "exit_code=" + outcome.exitCode() + " tail=" + outcome.tail());
        return Result.FAILED;
    }

    /**
     * Periodic regression: every {@code orange.post-merge.regression-every} good
     * builds, run the full suite. Counter comes from {@code task_events}:
     * {@code main_build_ok} occurrences since the most recent
     * {@code regression_pass}/{@code regression_fail}. No new schema needed.
     *
     * <p>Synchronous within {@code verifyAfterMerge} (already async from the
     * webhook), so a slow regression doesn't block the next webhook.
     */
    void maybeRunRegression(UUID taskId, Path repoPath) {
        if (regressionCommand == null || regressionCommand.isBlank()) return;
        int since = countMainBuildOksSinceLastRegression();
        if (since < regressionEvery) {
            log.debug("regression not due yet: {}/{} good builds since last run", since, regressionEvery);
            return;
        }
        log.info("regression cycle reached ({} builds); running on task {}", since, taskId);
        BuildOutcome outcome;
        try {
            outcome = runRegression(repoPath);
        } catch (Exception e) {
            log.warn("regression invocation threw for task {}: {}", taskId, e.getMessage());
            events.append(taskId, "orchestrator", "regression_fail",
                    asJsonb(Map.of("kind", "invocation", "detail", e.getMessage() == null ? "" : e.getMessage())));
            return;
        }
        if (outcome.exitCode() == 0) {
            events.append(taskId, "orchestrator", "regression_pass",
                    asJsonb(Map.of("command", regressionCommand)));
            log.info("regression passed at task {}", taskId);
        } else {
            events.append(taskId, "orchestrator", "regression_fail",
                    asJsonb(Map.of("kind", "test_failure",
                            "exit_code", outcome.exitCode(),
                            "tail", outcome.tail())));
            log.warn("regression failed at task {} (exit {})", taskId, outcome.exitCode());
            if (triageOnFail) {
                try {
                    taskService.createTriageTask("regression_periodic", List.of(taskId),
                            "Periodic regression failed at task " + taskId
                                    + "\nexit_code=" + outcome.exitCode()
                                    + "\ntail=\n" + outcome.tail());
                } catch (Exception e) {
                    log.warn("could not enqueue regression-triage task: {}", e.getMessage());
                }
            }
        }
    }

    private int countMainBuildOksSinceLastRegression() {
        Long lastRegressionId = jdbc.queryForObject(
                "SELECT COALESCE(MAX(id), 0) FROM task_events"
                        + " WHERE event_type IN ('regression_pass','regression_fail')",
                Long.class);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_events"
                        + " WHERE event_type = 'main_build_ok' AND id > ?",
                Long.class,
                lastRegressionId == null ? 0L : lastRegressionId);
        return count == null ? 0 : count.intValue();
    }

    private BuildOutcome runRegression(Path cwd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", regressionCommand)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            return new BuildOutcome(-1, "(timed out after " + timeout + ")");
        }
        byte[] stdout = p.getInputStream().readAllBytes();
        return new BuildOutcome(p.exitValue(), tail(new String(stdout)));
    }

    private void run(Path cwd, List<String> cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        Process p = pb.start();
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("git step timed out: " + String.join(" ", cmd));
        }
        if (p.exitValue() != 0) {
            String tail = new String(p.getInputStream().readAllBytes());
            throw new IOException("git step exited " + p.exitValue() + ": " + tail);
        }
    }

    private BuildOutcome runBuild(Path cwd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", buildCommand)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Process p = pb.start();
        boolean finished = p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            return new BuildOutcome(-1, "(timed out after " + timeout + ")");
        }
        byte[] stdout = p.getInputStream().readAllBytes();
        String text = new String(stdout);
        return new BuildOutcome(p.exitValue(), tail(text));
    }

    private static String tail(String s) {
        int max = 1024;
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    private void stampVerified(UUID taskId) {
        tasks.findById(taskId).ifPresent(t -> {
            Map<String, Object> meta = parseMetadata(t.metadata());
            meta.put("main_verified_at", OffsetDateTime.now().toString());
            tasks.updateMetadata(taskId, asJsonb(meta));
        });
    }

    private void recordFailure(UUID taskId, String kind, String detail) {
        log.warn("post-merge verify failed for task {}: {} — {}", taskId, kind, detail);
        events.append(taskId, "orchestrator", "build_broken",
                asJsonb(Map.of("kind", kind, "detail", detail == null ? "" : detail)));
        tasks.findById(taskId).ifPresent(t -> {
            Map<String, Object> meta = parseMetadata(t.metadata());
            meta.put("regression_suspect", true);
            meta.put("regression_suspect_at", OffsetDateTime.now().toString());
            tasks.updateMetadata(taskId, asJsonb(meta));
        });
        if (triageOnFail) {
            try {
                String description = "A post-merge build failed for the freshly merged task.\n\n"
                        + "Suspect task: " + taskId + "\n"
                        + "Failure kind: " + kind + "\n"
                        + "Detail (tail): " + (detail == null ? "" : detail);
                taskService.createTriageTask("regression", List.of(taskId), description);
                log.info("triage task enqueued for build_broken on task {}", taskId);
            } catch (Exception e) {
                log.warn("could not enqueue triage task for {}: {}", taskId, e.getMessage());
            }
        }
    }

    private Map<String, Object> parseMetadata(JSONB meta) {
        if (meta == null) return new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(meta.data(), Map.class);
            return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private JSONB asJsonb(Map<String, Object> map) {
        try {
            return JSONB.valueOf(json.writeValueAsString(map));
        } catch (JsonProcessingException e) {
            return JSONB.valueOf("{}");
        }
    }

    public enum Result { PASSED, FAILED, SKIPPED }

    private record BuildOutcome(int exitCode, String tail) {}
}
