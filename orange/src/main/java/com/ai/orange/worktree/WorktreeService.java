package com.ai.orange.worktree;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Manages per-task git worktrees on disk. One worktree per task UUID, rooted at
 * {@code orange.worktrees.root/<uuid>}, on a branch {@code orange/<uuid>} made from the task's
 * base ({@link TaskBase}).
 *
 * <p>A task of a project's repo has that repo's mirror as its base repository and the project's
 * base branch as its base (remote-first spec §7.1, {@link TaskBases}). A task of no repo has the
 * legacy {@code orange.worktrees.base-repo} and {@code base-branch}.
 *
 * <p>When the base has an {@code origin}, {@link #create} fetches first and branches from
 * {@code origin/<branch>}, so a worktree is never branched off a stale local ref.
 *
 * Agents run with the worktree as their working directory. Claude Code confines its file-editing
 * tools to that directory, but in process mode its Bash tool is not confined (spec §16: process
 * mode is for local development; container mode is the isolation boundary). This service is just
 * the file-system + git side.
 */
@Service
public class WorktreeService {

    private static final Logger log = LoggerFactory.getLogger(WorktreeService.class);
    private static final Duration GIT_TIMEOUT = Git.QUICK;

    private final WorktreeProperties props;
    private final TaskBases bases;

    /** Every task on the legacy base repo. */
    public WorktreeService(WorktreeProperties props) {
        this(props, TaskBases.NONE);
    }

    @Autowired
    public WorktreeService(WorktreeProperties props, TaskBases bases) {
        this.props = props;
        this.bases = bases;
    }

    @PostConstruct
    void ensureRootExists() throws IOException {
        Path root = rootPath();
        Files.createDirectories(root);
        log.info("worktree root = {}", root);
    }

    /**
     * The task's base: its repo's mirror and the project's base branch, or for a task of no repo
     * the legacy base repo.
     *
     * @throws IllegalStateException when the task has no repo and no base repo is configured
     */
    public TaskBase base(UUID taskId) {
        return bases.of(taskId).orElseGet(this::legacyBase);
    }

    private TaskBase legacyBase() {
        String repo = requireBaseRepo();
        String branch = props.baseBranch();
        boolean origin;
        try {
            origin = hasOriginRemote(repo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path dir = Path.of(repo).toAbsolutePath();
        Remote remote = !origin ? null : new Remote() {
            @Override
            public void fetch() {
                Git.run(dir, Git.SLOW, "fetch", "--quiet", "origin", branch);
            }

            @Override
            public void push(Path in, String... refspecs) {
                List<String> args = new java.util.ArrayList<>(List.of("push", "--quiet", "origin"));
                args.addAll(List.of(refspecs));
                Git.run(in, Git.SLOW, args.toArray(String[]::new));
            }
        };
        return new TaskBase(dir, branch, remote);
    }

    /**
     * Create a worktree for {@code taskId} at {@code root/<taskId>} on a fresh branch
     * {@code orange/<taskId>} from the task's base, fetched first when it has an origin. Refuses if
     * the global worktree count is at the configured cap.
     */
    public Path create(UUID taskId) throws IOException, InterruptedException {
        TaskBase base = base(taskId);
        Path root = rootPath();
        requireCapacity(root, taskId);
        Path target = root.resolve(taskId.toString()).toAbsolutePath();
        if (Files.exists(target)) {
            throw new IllegalStateException("worktree already exists: " + target);
        }
        try {
            base.fetch();
        } catch (GitException e) {
            throw new IOException(e.getMessage(), e);
        }
        String sourceRef = base.ref();
        String branch = "orange/" + taskId;
        runGit(base.repo().toString(), List.of("worktree", "add", "-b", branch, target.toString(), sourceRef));
        log.info("created worktree taskId={} path={} sourceRef={} branch={}", taskId, target, sourceRef, branch);
        return target;
    }

    /**
     * The task's worktree, created on first use. Every later run of the task gets the same
     * worktree and branch back, with the earlier runs' commits. If only the directory is gone
     * (disposed of, or removed by hand) but the branch {@code orange/<taskId>} is still there,
     * the branch is checked out again rather than started over from the base.
     */
    public Path ensure(UUID taskId) throws IOException, InterruptedException {
        Path target = resolve(taskId);
        // A linked worktree has a .git file pointing at the base repo.
        if (Files.exists(target.resolve(".git"))) return target;
        if (Files.exists(target)) {
            log.warn("{} is not a git worktree; recreating it", target);
            remove(taskId);
        }
        String repo = base(taskId).repo().toString();
        String branch = "orange/" + taskId;
        if (runGitOrTimeoutOrMissing(repo, List.of("rev-parse", "--verify", "--quiet", "refs/heads/" + branch)) != 0) {
            return create(taskId);
        }
        requireCapacity(rootPath(), taskId);
        runGit(repo, List.of("worktree", "prune"));
        runGit(repo, List.of("worktree", "add", target.toString(), branch));
        log.info("re-attached worktree taskId={} path={} branch={}", taskId, target, branch);
        return target;
    }

    private void requireCapacity(Path root, UUID taskId) throws IOException {
        long existing = countExisting(root);
        if (existing >= props.maxCount()) {
            throw new WorktreeCapacityException(
                    "worktree cap reached (" + existing + "/" + props.maxCount() + "); cannot create " + taskId);
        }
    }

    /**
     * Returns true iff the base repo has an {@code origin} remote configured.
     * git distinguishes "no such remote" (exit 2) from other failures; only
     * the former is silently treated as "no remote". Any other non-zero exit
     * (e.g. corrupt config) bubbles up so we don't accidentally fall through
     * to the stale-local-ref path. Timeouts throw via {@link #runGitOrTimeoutOrMissing}.
     */
    private boolean hasOriginRemote(String repo) throws IOException {
        int exit = runGitOrTimeoutOrMissing(repo, List.of("remote", "get-url", "origin"));
        if (exit == 0) return true;
        if (exit == 2) return false;
        throw new IOException("git remote get-url origin failed with exit " + exit
                + " (not the 'remote not found' case); refusing to fall back to local ref");
    }

    /**
     * Remove the worktree for {@code taskId}. Idempotent: if the directory
     * doesn't exist we still {@code git worktree prune} so the registry stays clean.
     *
     * <p>If the directory exists but git refuses to remove it as a worktree
     * (e.g. "is not a working tree" — an orphaned dir left by a partial/failed
     * removal, or a nested leftover), we delete the directory outright rather
     * than fail. Otherwise the post-merge disposer would retry the same broken
     * path every sweep forever, spamming logs and never freeing the worktree
     * slot against {@code orange.worktrees.max-count}.
     */
    public void remove(UUID taskId) throws IOException, InterruptedException {
        String repo = base(taskId).repo().toString();
        Path target = rootPath().resolve(taskId.toString()).toAbsolutePath();
        if (Files.exists(target)) {
            try {
                runGit(repo, List.of("worktree", "remove", "--force", target.toString()));
                log.info("removed worktree taskId={}", taskId);
            } catch (IOException e) {
                // A timeout means git is hung (e.g. a stuck index lock), not that
                // the dir is an orphan — propagate rather than blindly deleting a
                // possibly-live worktree (same fail-loud-on-timeout posture as
                // runGitOrTimeoutOrMissing). Any other failure (exit 128 "is not a
                // working tree", etc.) means git won't manage this dir, so we do.
                if (e.getMessage() != null && e.getMessage().contains("timed out")) {
                    throw e;
                }
                log.warn("git worktree remove failed for task {}; deleting dir directly: {}",
                        taskId, e.getMessage());
                deleteRecursively(target);
                log.info("deleted stale worktree dir taskId={}", taskId);
            }
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

    /** Brings the task's base ref up to date; a no-op without an origin. */
    public void fetchBase(UUID taskId) {
        base(taskId).fetch();
    }

    /** Whether one more worktree fits under {@code orange.worktrees.max-count}. */
    public boolean hasCapacity() {
        try {
            return countExisting(rootPath()) < props.maxCount();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Absolute, canonical worktree root. A <em>relative</em> {@code
     * orange.worktrees.root} is resolved against the configured base repo, NOT
     * the process working directory. Otherwise any orange process whose CWD
     * happens to be inside a worktree (e.g. an agent running the app or tests
     * from its own worktree) would compute a root nested inside that worktree
     * and create worktrees-within-worktrees. Anchoring to the base repo makes
     * the root deterministic regardless of who computes it.
     */
    public Path rootPath() {
        Path root = Path.of(props.root());
        if (!root.isAbsolute()) {
            String base = props.baseRepo();
            if (base != null && !base.isBlank()) {
                root = Path.of(base).resolve(root);
            }
        }
        return root.toAbsolutePath().normalize();
    }

    private String requireBaseRepo() {
        String repo = props.baseRepo();
        if (repo == null || repo.isBlank()) {
            throw new IllegalStateException("orange.worktrees.base-repo is not configured");
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

    private void runGit(String repo, List<String> args) throws IOException {
        try {
            Git.run(Path.of(repo), GIT_TIMEOUT, args.toArray(String[]::new));
        } catch (GitException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /**
     * Run git, swallow non-zero exits, return the exit code. Timeouts throw
     * (so callers don't confuse "command timed out" with "exited normally with
     * code N") — this is the same fail-loud posture as {@link #runGit}.
     */
    private int runGitOrTimeoutOrMissing(String repo, List<String> args) throws IOException {
        try {
            return Git.exitCode(Path.of(repo), GIT_TIMEOUT, args.toArray(String[]::new));
        } catch (GitException e) {
            throw new IOException(e.getMessage(), e);
        }
    }
}
