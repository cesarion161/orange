package com.ai.orange.repo;

import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.GitAuth;
import com.ai.orange.worktree.GitException;
import com.ai.orange.worktree.Remote;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * orange's mirrors of GitHub repos (remote-first spec §7.1): one bare repository per repo at
 * {@code <root>/<owner>/<name>.git}, with GitHub as {@code origin} and its branches as
 * {@code origin/*}. Task worktrees and the scratch checkouts that close a session's work are made
 * from it, and only orange's own git ever runs in it.
 *
 * <p>Every git command that talks to GitHub goes through here: one at a time per repo (concurrent
 * fetches of one repository fight over its ref locks), authenticated with the owner's token when
 * there is one (builder's call 6).
 */
@Component
public class Mirrors {

    private static final Logger log = LoggerFactory.getLogger(Mirrors.class);

    private final RepoProperties props;
    private final ConcurrentHashMap<RepoKey, ReentrantLock> locks = new ConcurrentHashMap<>();

    public Mirrors(RepoProperties props) {
        this.props = props;
    }

    public Path path(RepoKey key) {
        return Path.of(props.root()).toAbsolutePath().normalize().resolve(key.owner()).resolve(key.name() + ".git");
    }

    /**
     * The mirror of {@code key}, cloned when there is none. An existing mirror is not fetched. A
     * directory at its place that is not a bare repository (a clone a crash cut short) is made again.
     *
     * @throws GitException when GitHub cannot be read
     */
    public Path ensure(RepoKey key) {
        Path mirror = path(key);
        if (isMirror(mirror)) return mirror;
        return locked(key, () -> {
            if (isMirror(mirror)) return mirror;
            clone(key, mirror);
            return mirror;
        });
    }

    /** Brings the mirror's {@code origin/*} up to date with GitHub, cloning it first when needed. */
    public void fetch(RepoKey key) {
        Path mirror = ensure(key);
        locked(key, () -> Git.run(mirror, Git.SLOW, auth(key), "fetch", "--quiet", "--prune", "origin"));
    }

    /** Fetch and push for {@code key}, through this class. */
    public Remote remote(RepoKey key) {
        return new Remote() {
            @Override
            public void fetch() {
                Mirrors.this.fetch(key);
            }

            @Override
            public void push(Path dir, String... refspecs) {
                List<String> args = new ArrayList<>(List.of("push", "--quiet", "origin"));
                args.addAll(List.of(refspecs));
                locked(key, () -> Git.run(dir, Git.SLOW, auth(key), args.toArray(String[]::new)));
            }

            @Override
            public String toString() {
                return "origin of " + key;
            }
        };
    }

    /** The owner's token when the repo is reached over https, else no credentials at all. */
    public GitAuth auth(RepoKey key) {
        if (!key.cloneUrl(props.githubUrl()).startsWith("https://")) return GitAuth.anonymous();
        return props.tokenFor(key.owner()).map(GitAuth::token).orElseGet(GitAuth::anonymous);
    }

    /** GitHub's default branch, as the mirror last saw it ({@code origin/HEAD}). */
    public Optional<String> defaultBranch(RepoKey key) {
        Path mirror = path(key);
        if (!isMirror(mirror)
                || Git.exitCode(mirror, Git.QUICK, "symbolic-ref", "--quiet", "refs/remotes/origin/HEAD") != 0) {
            return Optional.empty();
        }
        String ref = Git.run(mirror, Git.QUICK, "symbolic-ref", "--short", "refs/remotes/origin/HEAD").strip();
        return Optional.of(ref.startsWith("origin/") ? ref.substring("origin/".length()) : ref).filter(b -> !b.isBlank());
    }

    private void clone(RepoKey key, Path mirror) {
        Path tmp = mirror.resolveSibling(mirror.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(mirror.getParent());
            deleteRecursively(mirror);
            Git.run(mirror.getParent(), Git.QUICK, "init", "--quiet", "--bare", tmp.toString());
            Git.run(tmp, Git.QUICK, "remote", "add", "origin", key.cloneUrl(props.githubUrl()));
            Git.run(tmp, Git.SLOW, auth(key), "fetch", "--quiet", "--prune", "origin");
            if (Git.exitCode(tmp, Git.SLOW, auth(key), "remote", "set-head", "origin", "--auto") != 0) {
                log.warn("could not learn the default branch of {}", key);
            }
            Files.move(tmp, mirror, StandardCopyOption.ATOMIC_MOVE);
            log.info("mirrored {} at {}", key, mirror);
        } catch (IOException e) {
            throw new UncheckedIOException("could not make the mirror of " + key + " at " + mirror, e);
        } finally {
            try {
                deleteRecursively(tmp);
            } catch (IOException e) {
                log.warn("could not remove {}: {}", tmp, e.getMessage());
            }
        }
    }

    /** Whether {@code dir} is a bare git repository of its own (never one of a directory above it). */
    private static boolean isMirror(Path dir) {
        if (!Files.isDirectory(dir)) return false;
        try {
            return Git.exitCode(dir.getParent(), Git.QUICK, "--git-dir=" + dir, "rev-parse", "--is-bare-repository") == 0
                    && Git.run(dir.getParent(), Git.QUICK, "--git-dir=" + dir, "rev-parse", "--is-bare-repository")
                            .strip().equals("true");
        } catch (GitException e) {
            return false;
        }
    }

    private <T> T locked(RepoKey key, Supplier<T> work) {
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock();
        try {
            return work.get();
        } finally {
            lock.unlock();
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path f : walk.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(f);
        }
    }
}
