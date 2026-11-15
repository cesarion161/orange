package com.ai.orange.repo;

import com.ai.orange.worktree.Git;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** A directory of bare repositories that stands in for GitHub: {@code orange.repos.github-url} points at it. */
public final class FakeGithub {

    private final Path root;

    public FakeGithub(Path root) {
        this.root = root;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code file://<root>/}, the value for {@code orange.repos.github-url}. */
    public String url() {
        return root.toUri().toString().endsWith("/") ? root.toUri().toString() : root.toUri() + "/";
    }

    public Path bare(RepoKey key) {
        return root.resolve(key.owner()).resolve(key.name() + ".git");
    }

    /** A repository with one commit adding README.md on {@code main}. */
    public RepoKey create(String ownerSlashName) {
        return create(ownerSlashName, "main");
    }

    /** A repository with one commit adding README.md on {@code branch}, its default branch. */
    public RepoKey create(String ownerSlashName, String branch) {
        RepoKey key = RepoKey.of(ownerSlashName);
        Path bare = bare(key);
        try {
            Files.createDirectories(bare.getParent());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Git.run(bare.getParent(), Git.QUICK, "init", "-q", "--bare", "-b", branch, bare.toString());
        commit(key, branch, "README.md", "# " + key.name() + "\n");
        return key;
    }

    /** Commits {@code file} on {@code branch} (made from the default branch when new) and pushes it. */
    public String commit(RepoKey key, String branch, String file, String content) {
        Path work = root.resolve(".work-" + UUID.randomUUID());
        try {
            Git.run(root, Git.QUICK, "clone", "-q", bare(key).toString(), work.toString());
            Git.run(work, Git.QUICK, "config", "user.email", "gh@e");
            Git.run(work, Git.QUICK, "config", "user.name", "GH");
            if (Git.exitCode(work, Git.QUICK, "rev-parse", "--verify", "--quiet", "origin/" + branch) == 0) {
                Git.run(work, Git.QUICK, "checkout", "-q", "-B", branch, "origin/" + branch);
            } else {
                Git.run(work, Git.QUICK, "checkout", "-q", "-B", branch);
            }
            Path target = work.resolve(file);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, content);
            Git.run(work, Git.QUICK, "add", "-A");
            Git.run(work, Git.QUICK, "commit", "-q", "-m", "gh: " + file);
            Git.run(work, Git.QUICK, "push", "-q", "origin", branch);
            return Git.head(work);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            delete(work);
        }
    }

    /** The commit {@code branch} points at, or null when there is no such branch. */
    public String head(RepoKey key, String branch) {
        Path bare = bare(key);
        if (Git.exitCode(bare, Git.QUICK, "rev-parse", "--verify", "--quiet", "refs/heads/" + branch) != 0) return null;
        return Git.run(bare, Git.QUICK, "rev-parse", "refs/heads/" + branch).strip();
    }

    static void delete(Path p) {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            walk.sorted((a, b) -> b.compareTo(a)).forEach(f -> f.toFile().delete());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
