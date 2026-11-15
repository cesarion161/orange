package com.ai.orange.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.GitException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** One bare mirror per GitHub repo (remote-first spec §7.1), on bare repos standing in for GitHub. */
class MirrorsIT {

    @TempDir Path tmp;
    FakeGithub github;
    Mirrors mirrors;
    Path root;

    @BeforeEach
    void setUp() {
        github = new FakeGithub(tmp.resolve("github"));
        root = tmp.resolve("mirrors");
        mirrors = new Mirrors(new RepoProperties(root.toString(), github.url(), ""));
    }

    @Test
    void ensureClonesOnceAndFetchSeesNewCommits() {
        RepoKey key = github.create("me/one");
        Path mirror = mirrors.ensure(key);

        assertThat(mirror).isEqualTo(root.resolve("me").resolve("one.git"));
        assertThat(Git.run(mirror, Git.QUICK, "rev-parse", "--is-bare-repository").strip()).isEqualTo("true");
        assertThat(rev(mirror, "origin/main")).isEqualTo(github.head(key, "main"));

        String next = github.commit(key, "main", "b.txt", "b\n");
        assertThat(mirrors.ensure(key)).isEqualTo(mirror);
        assertThat(rev(mirror, "origin/main")).as("ensure does not fetch an existing mirror").isNotEqualTo(next);
        mirrors.fetch(key);
        assertThat(rev(mirror, "origin/main")).isEqualTo(next);
    }

    @Test
    void theDefaultBranchComesFromOriginHead() {
        RepoKey key = github.create("me/trunky", "trunk");
        mirrors.ensure(key);
        assertThat(mirrors.defaultBranch(key)).contains("trunk");
    }

    @Test
    void pushGoesToGithub() {
        RepoKey key = github.create("me/pushy");
        Path mirror = mirrors.ensure(key);
        Path wt = tmp.resolve("wt");
        Git.run(mirror, Git.QUICK, "worktree", "add", "-q", "--detach", wt.toString(), "origin/main");
        Git.run(wt, Git.QUICK, "-c", "user.name=T", "-c", "user.email=t@e", "commit", "-q", "--allow-empty", "-m", "x");
        String sha = Git.head(wt);

        mirrors.remote(key).push(wt, sha + ":refs/heads/main");

        assertThat(github.head(key, "main")).isEqualTo(sha);
    }

    @Test
    void parallelFetchesOfOneMirrorBothSucceed() throws Exception {
        RepoKey key = github.create("me/busy");
        mirrors.ensure(key);
        for (int i = 0; i < 5; i++) github.commit(key, "b" + i, "f" + i, "x");
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    mirrors.fetch(key);
                } catch (Throwable t) {
                    errors.add(t);
                }
            }));
        }
        for (Thread t : threads) t.join();
        assertThat(errors).isEmpty();
        assertThat(rev(mirrors.path(key), "origin/b4")).isEqualTo(github.head(key, "b4"));
    }

    @Test
    void aMirrorDirectoryWithoutAGitRepoIsMadeAgain() throws Exception {
        RepoKey key = github.create("me/broken");
        Path half = mirrors.path(key);
        Files.createDirectories(half);
        Files.writeString(half.resolve("stray"), "left by a crash");

        Path mirror = mirrors.ensure(key);

        assertThat(rev(mirror, "origin/main")).isEqualTo(github.head(key, "main"));
        assertThat(mirror.resolve("stray")).doesNotExist();
    }

    @Test
    void theTokenIsNowhereOnDisk() throws Exception {
        Mirrors withToken = new Mirrors(new RepoProperties(root.toString(), "https://example.invalid/", "me=tok-zz9"));
        RepoKey key = RepoKey.of("me/secret");
        assertThat(withToken.auth(key).toString()).doesNotContain("tok-zz9");
        assertThatThrownBy(() -> withToken.ensure(key))
                .isInstanceOf(GitException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("tok-zz9"));
        assertThat(withToken.path(key)).doesNotExist();
        if (Files.exists(root)) {
            try (Stream<Path> files = Files.walk(root)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    assertThat(new String(Files.readAllBytes(f), StandardCharsets.ISO_8859_1)).as(f.toString())
                            .doesNotContain("tok-zz9");
                }
            }
        }
    }

    @Test
    void anUnknownRepoFailsWithGitException() {
        RepoKey key = RepoKey.of("nobody/none");
        assertThatThrownBy(() -> mirrors.ensure(key)).isInstanceOf(GitException.class);
        assertThat(mirrors.path(key)).doesNotExist();
        try (Stream<Path> left = Files.exists(root.resolve("nobody")) ? Files.list(root.resolve("nobody")) : Stream.empty()) {
            assertThat(left.toList()).as("no temp directory is left").isEmpty();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static String rev(Path repo, String ref) {
        return Git.run(repo, Git.QUICK, "rev-parse", ref).strip();
    }
}
