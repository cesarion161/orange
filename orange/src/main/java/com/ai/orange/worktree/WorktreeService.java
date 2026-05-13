package com.ai.orange.worktree;

import jakarta.annotation.PostConstruct;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Manages per-task git worktrees on disk. One worktree per task UUID, rooted at
 * {@code orange.worktrees.root/<uuid>}, branched off the configured base repo.
 *
 * The {@code agent-runner} sandbox ({@code chdir_guard} + {@code worktree_guard}
 * in {@code hooks.py}) enforces the path boundary at the agent level — this
 * service is just the file-system + git side.
 */
@Service
public class WorktreeService {

    private static final Logger log = LoggerFactory.getLogger(WorktreeService.class);
    private static final long GIT_TIMEOUT_SECONDS = 60;

    private final WorktreeProperties props;

    public WorktreeService(WorktreeProperties props) {
        this.props = props;
    }

    @PostConstruct
    void ensureRootExists() throws IOException {
        Path root = Path.of(props.root()).toAbsolutePath();
        Files.createDirectories(root);
        log.info("worktree root = {}", root);
    }

    public Path create(UUID taskId, String baseRef) throws IOException, InterruptedException {
        return create(taskId, baseRef, props.baseRepo());
    }

    /**
     * Create a worktree for {@code taskId} at {@code root/<taskId>}, branched
     * off {@code baseRef} of {@code baseRepo} on a fresh branch named
     * {@code orange/<taskId>}. Refuses if the global worktree count is at the
     * configured cap.
     */
    public Path create(UUID taskId, String baseRef, String baseRepoOverride) throws IOException, InterruptedException {
        String repo = requireBaseRepo(baseRepoOverride);
        Path root = rootPath();
        long existing = countExisting(root);
        if (existing >= props.maxCount()) {
            throw new IllegalStateException(
                    "worktree cap reached (" + existing + "/" + props.maxCount() + "); cannot create " + taskId);
        }
        Path target = root.resolve(taskId.toString()).toAbsolutePath();
        if (Files.exists(target)) {
            throw new IllegalStateException("worktree already exists: " + target);
        }

        String branch = "orange/" + taskId;
        runGit(repo, List.of("worktree", "add", "-b", branch, target.toString(), baseRef));
        log.info("created worktree taskId={} path={} baseRef={} branch={}", taskId, target, baseRef, branch);
        return target;
    }

    /**
     * Remove the worktree for {@code taskId}. Idempotent: if the directory
     * doesn't exist we still {@code git worktree prune} so the registry stays clean.
     */
    public void remove(UUID taskId) throws IOException, InterruptedException {
        remove(taskId, props.baseRepo());
    }

    public void remove(UUID taskId, String baseRepoOverride) throws IOException, InterruptedException {
        String repo = requireBaseRepo(baseRepoOverride);
        Path target = rootPath().resolve(taskId.toString()).toAbsolutePath();
        if (Files.exists(target)) {
            runGit(repo, List.of("worktree", "remove", "--force", target.toString()));
            log.info("removed worktree taskId={}", taskId);
        }
        runGit(repo, List.of("worktree", "prune"));
    }

    /**
     * Best-effort: scan the worktree root and remove any directory whose name
     * isn't a valid UUID, plus prune git's registry. Intended to run on startup.
     * The richer "remove worktrees whose tasks are in terminal state" pass is
     * a Phase 6 concern (needs the workflow layer to know task lifecycles).
     */
    public int gcStale() throws IOException, InterruptedException {
        Path root = rootPath();
        if (!Files.exists(root)) return 0;
        int removed = 0;
        try (Stream<Path> entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                if (!Files.isDirectory(entry)) continue;
                if (!isUuid(entry.getFileName().toString())) {
                    log.warn("removing non-UUID worktree dir: {}", entry);
                    deleteRecursively(entry);
                    removed++;
                }
            }
        }
        if (!props.baseRepo().isBlank()) {
            runGit(props.baseRepo(), List.of("worktree", "prune"));
        }
        return removed;
    }

    public Path resolve(UUID taskId) {
        return rootPath().resolve(taskId.toString()).toAbsolutePath();
    }

    public Path rootPath() {
        return Path.of(props.root()).toAbsolutePath();
    }

    private String requireBaseRepo(String override) {
        String repo = (override != null && !override.isBlank()) ? override : props.baseRepo();
        if (repo == null || repo.isBlank()) {
            throw new IllegalStateException(
                    "orange.worktrees.base-repo is not configured; set it or pass an override");
        }
        return repo;
    }

    private long countExisting(Path root) throws IOException {
        if (!Files.exists(root)) return 0;
        try (Stream<Path> entries = Files.list(root)) {
            return entries.filter(Files::isDirectory).count();
        }
    }

    private static boolean isUuid(String s) {
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(child -> {
                try {
                    Files.deleteIfExists(child);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private void runGit(String repo, List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new java.util.ArrayList<>(args.size() + 1);
        cmd.add("git");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(Path.of(repo).toFile()).redirectErrorStream(true);
        Process proc = pb.start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                out.append(line).append('\n');
            }
        }
        if (!proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new IOException("git " + args + " timed out after " + GIT_TIMEOUT_SECONDS + "s");
        }
        int code = proc.exitValue();
        if (code != 0) {
            throw new IOException("git " + args + " failed (exit " + code + "):\n" + out);
        }
    }
}
