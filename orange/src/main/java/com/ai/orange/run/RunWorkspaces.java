package com.ai.orange.run;

import com.ai.orange.harness.container.ContainerRuntime;
import com.ai.orange.harness.container.ContainerSpec;
import com.ai.orange.harness.container.ContainerUnavailableException;
import com.ai.orange.harness.container.DockerCommand;
import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.GitException;
import com.ai.orange.worktree.WorktreeService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Where a contained run works, and how its commits come back (v2 PR 6).
 *
 * <p>The rule: no container gets write access to a directory in which orange runs git, and orange
 * never runs git in a directory a container wrote. git executes what a repository's config names
 * ({@code core.fsmonitor}, {@code core.sshCommand}, {@code diff.external}), and whoever writes a
 * worktree can replace its {@code .git}. So a coding run gets a clone of its own under the run's
 * directory, the task's worktree stays orange's, and the only thing that crosses back is one
 * bundle file: written by git in a container without a network, read on the host as a pack.
 */
@Component
public class RunWorkspaces {

    private static final Logger log = LoggerFactory.getLogger(RunWorkspaces.class);
    static final String WORKSPACE = "workspace";
    static final String EXPORT = "export";
    private static final String BUNDLE = "head.bundle";
    private static final Duration EXPORT_TIMEOUT = Duration.ofMinutes(2);
    /**
     * Runs in the workspace: {@code $1} = where the run started, {@code $2} = the bundle to write.
     * 3: no readable HEAD. 4: HEAD is where the run started. 5: no bundle (HEAD moved behind its
     * start, or git could not write one).
     */
    private static final String EXPORT_SCRIPT = """
            head=$(git rev-parse --verify --quiet HEAD) || exit 3
            [ "$head" = "$1" ] && exit 4
            git bundle create "$2" "$1..HEAD" >/dev/null 2>&1 || exit 5
            """;
    private static final int EXIT_NOTHING_NEW = 4;
    private static final Set<PosixFilePermission> OWNER_ALL = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);

    private final WorktreeService worktrees;
    private final ContainerRuntime containers;
    private final RunsProperties runsProps;

    public RunWorkspaces(WorktreeService worktrees, ContainerRuntime containers, RunsProperties runsProps) {
        this.worktrees = worktrees;
        this.containers = containers;
        this.runsProps = runsProps;
    }

    /** What {@link #bringHome} did. */
    public enum Home {
        /** The task's worktree is at the run's HEAD. */
        IMPORTED,
        /** The run's HEAD is where it started. */
        NOTHING_NEW,
        /** What the run left could not be read or does not fit the task's branch; nothing was imported. */
        UNREADABLE,
        /** Docker could not run the export; nothing was imported. */
        CONTAINER_UNAVAILABLE
    }

    /**
     * {@code <runDir>/workspace}: a clone of {@code orange/<taskId>} that shares nothing with the
     * base repo (no hardlinks, no alternates, no remote) and holds the task's branch and the base
     * ref only. Made by orange's git before any container has seen the directory.
     *
     * @throws GitException when the branch or the base ref cannot be cloned
     */
    public Path cloneOf(UUID taskId, Path runDir) throws IOException {
        Path base = worktrees.baseRepo();
        Path workspace = runDir.toAbsolutePath().resolve(WORKSPACE);
        Files.createDirectories(workspace.getParent());
        // --no-local: git's own transport sends what the branch reaches, and nothing else.
        Git.run(base, Git.SLOW, "clone", "--quiet", "--no-local", "--no-tags", "--single-branch",
                "--branch", "orange/" + taskId, base.toString(), workspace.toString());
        Git.run(workspace, Git.QUICK, "remote", "remove", "origin");
        // The base under the name the coding prompt uses: a fix run after a conflict rebases onto it.
        String ref = worktrees.hasOrigin() ? "refs/remotes/origin/" + worktrees.baseBranch()
                : "refs/heads/" + worktrees.baseBranch();
        Git.run(workspace, Git.SLOW, "fetch", "--quiet", "--no-tags", base.toString(), "+" + ref + ":" + ref);
        return workspace;
    }

    /**
     * Brings what the run committed into the task's worktree: the branch becomes what the run's
     * {@code HEAD} is, as in process mode, where an agent may amend. The run's container has
     * exited. Never throws.
     *
     * @param workspace the run's clone ({@link #cloneOf})
     * @param startSha  the head of the task's branch when the clone was made
     */
    public Home bringHome(Run run, Path workspace, Path runDir, String startSha) {
        Path export = runDir.toAbsolutePath().resolve(EXPORT);
        Path bundle = export.resolve(BUNDLE);
        ContainerRuntime.Result exported;
        try {
            Files.createDirectories(export);
            // git reads what the agent left (its .git, its config, whatever its links point at) in here only.
            exported = containers.run(new ContainerSpec(DockerCommand.runName(run.id()) + "-export",
                    DockerCommand.labels(run.id(), "export", runsProps.workerId()), workspace,
                    List.of(new ContainerSpec.Mount(workspace, true), new ContainerSpec.Mount(export, false)),
                    Map.of("HOME", containers.settings().home()), Map.of(), false,
                    List.of("sh", "-c", EXPORT_SCRIPT, "export", startSha, bundle.toString())), EXPORT_TIMEOUT);
        } catch (ContainerUnavailableException e) {
            log.error("run {}: its commits cannot be exported: {}", run.id(), e.getMessage());
            return Home.CONTAINER_UNAVAILABLE;
        } catch (IOException | RuntimeException e) {
            log.error("run {}: its commits cannot be exported: {}", run.id(), e.toString());
            return Home.UNREADABLE;
        }
        if (exported.exitCode() == EXIT_NOTHING_NEW) return Home.NOTHING_NEW;
        if (DockerCommand.dockerFailed(exported.exitCode())) {
            log.error("run {}: docker could not run the export (exit {}): {}", run.id(), exported.exitCode(),
                    exported.output().strip());
            return Home.CONTAINER_UNAVAILABLE;
        }
        if (exported.exitCode() != 0) {
            log.warn("run {}: what it left cannot be exported (exit {}): {}", run.id(), exported.exitCode(),
                    exported.output().strip());
            return Home.UNREADABLE;
        }
        try {
            if (!Files.isRegularFile(bundle, LinkOption.NOFOLLOW_LINKS)) {
                log.warn("run {}: the export left no bundle file", run.id());
                return Home.UNREADABLE;
            }
            Path worktree = worktrees.resolve(run.taskId());
            String head = Git.head(worktree);
            if (!head.equals(startSha)) {
                log.warn("run {}: the task's worktree moved from {} to {} while the run worked; not importing",
                        run.id(), startSha, head);
                return Home.UNREADABLE;
            }
            // One regular file, parsed as a pack and checked object by object.
            Git.run(worktree, Git.SLOW, "-c", "transfer.fsckObjects=true", "fetch", "--quiet", "--no-tags",
                    bundle.toString(), "HEAD");
            Git.run(worktree, Git.QUICK, "reset", "--quiet", "--hard", "FETCH_HEAD");
            return Home.IMPORTED;
        } catch (RuntimeException e) {
            log.warn("run {}: its bundle could not be imported: {}", run.id(), e.getMessage());
            return Home.UNREADABLE;
        }
    }

    /**
     * Deletes the run's clone and export once none of the run's containers is left: whatever still
     * carries its label is removed first. When docker cannot say that they are gone, the
     * directories stay for {@link RunRetention}: orange walks no tree that a container may still
     * be writing, where a directory can turn into a link between two looks. Never throws.
     *
     * @return false when the directories were left
     */
    public boolean discard(UUID runId, Path runDir) {
        if (containers.removeByRun(runId) < 0) {
            log.warn("run {}: docker cannot say that its containers are gone; its clone under {} stays until the "
                    + "run's directory is deleted", runId, runDir);
            return false;
        }
        discard(runDir);
        return true;
    }

    /**
     * Deletes {@code <runDir>/workspace} and {@code <runDir>/export}. Only for directories no
     * container holds; a run's own go through {@link #discard(UUID, Path)}. Never throws.
     */
    public void discard(Path runDir) {
        for (String name : List.of(WORKSPACE, EXPORT)) {
            Path dir = runDir.toAbsolutePath().resolve(name);
            try {
                delete(dir);
            } catch (IOException | RuntimeException e) {
                log.warn("could not delete {}: {}", dir, e.toString());
            }
        }
    }

    /** Deletes {@code root} and everything below it without following links, read-only directories included. */
    static void delete(Path root) throws IOException {
        try {
            deleteTree(root, false);
        } catch (IOException refused) {
            // Build tools leave read-only trees behind.
            deleteTree(root, true);
        }
    }

    /**
     * Deletes {@code root} and everything below it without following links.
     *
     * @param makeWritable gives the owner every right on each directory first
     */
    private static void deleteTree(Path root, boolean makeWritable) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Deque<Path> pending = new ArrayDeque<>();
        pending.push(root);
        while (!pending.isEmpty()) {
            Path p = pending.peek();
            List<Path> entries = List.of();
            if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                if (makeWritable) {
                    try {
                        Files.setPosixFilePermissions(p, OWNER_ALL);
                    } catch (UnsupportedOperationException ignored) {
                        // not a POSIX file system
                    }
                }
                try (Stream<Path> list = Files.list(p)) {
                    entries = list.toList();
                } catch (NoSuchFileException gone) {
                    // Someone else deleted it meanwhile (the reaper and the run's own executor may both discard).
                }
            }
            if (entries.isEmpty()) {
                Files.deleteIfExists(p);
                pending.pop();
            } else {
                // The directory stays below its entries and is looked at again when they are gone.
                entries.forEach(pending::push);
            }
        }
    }
}
