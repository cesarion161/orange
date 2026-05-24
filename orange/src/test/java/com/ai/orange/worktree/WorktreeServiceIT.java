package com.ai.orange.worktree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link WorktreeService} against a real (tiny) git repository
 * created per test in an isolated temp dir.
 */
@EnabledIf("isGitAvailable")
class WorktreeServiceIT {

    @TempDir Path tempDir;

    private Path baseRepo;
    private Path worktreesRoot;

    @BeforeEach
    void initBaseRepo() throws Exception {
        baseRepo = tempDir.resolve("base");
        worktreesRoot = tempDir.resolve("worktrees");
        Files.createDirectories(baseRepo);
        run(baseRepo, "git", "init", "-q", "-b", "main");
        run(baseRepo, "git", "config", "user.email", "test@example.com");
        run(baseRepo, "git", "config", "user.name", "Test");
        Files.writeString(baseRepo.resolve("README.md"), "hello\n");
        run(baseRepo, "git", "add", ".");
        run(baseRepo, "git", "commit", "-q", "-m", "initial");
    }

    static boolean isGitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private WorktreeService service(int maxCount) throws IOException {
        WorktreeProperties props = new WorktreeProperties(
                worktreesRoot.toString(), baseRepo.toString(), "main", maxCount);
        WorktreeService svc = new WorktreeService(props);
        // Manually invoke the @PostConstruct since we're not in a Spring context.
        Files.createDirectories(worktreesRoot);
        return svc;
    }

    @Test
    void root_path_resolves_relative_root_against_base_repo_not_cwd() {
        // A relative worktrees.root must anchor to the base repo, not the
        // process CWD — otherwise a process whose CWD is inside a worktree
        // would nest new worktrees inside it.
        WorktreeProperties props = new WorktreeProperties(
                ".orange-worktrees", baseRepo.toString(), "main", 20);
        Path root = new WorktreeService(props).rootPath();

        assertThat(root).isAbsolute()
                .isEqualTo(baseRepo.resolve(".orange-worktrees").toAbsolutePath().normalize());
    }

    @Test
    void create_then_remove_round_trip() throws Exception {
        WorktreeService svc = service(20);
        UUID taskId = UUID.randomUUID();

        Path worktree = svc.create(taskId);
        assertThat(worktree).exists().isDirectory();
        assertThat(worktree.resolve("README.md")).exists();
        assertThat(worktree.getFileName().toString()).isEqualTo(taskId.toString());

        // Branch should be created on the base repo too.
        String branches = capture(baseRepo, "git", "branch", "--list", "orange/" + taskId);
        assertThat(branches).contains("orange/" + taskId);

        svc.remove(taskId);
        assertThat(worktree).doesNotExist();
    }

    @Test
    void remove_deletes_orphaned_dir_that_is_not_a_registered_worktree() throws Exception {
        WorktreeService svc = service(20);
        UUID taskId = UUID.randomUUID();

        // Simulate the disposer's problem case: a directory left under the
        // worktree root that git no longer recognizes as a worktree (orphaned
        // by a partial/failed removal). `git worktree remove` fails here with
        // "is not a working tree"; remove() must still clean it up rather than
        // throw and let the disposer retry it forever.
        Path orphan = worktreesRoot.resolve(taskId.toString());
        Files.createDirectories(orphan);
        Files.writeString(orphan.resolve("leftover.txt"), "stale\n");

        svc.remove(taskId); // must not throw

        assertThat(orphan).doesNotExist();
    }

    @Test
    void create_refuses_when_max_count_reached() throws Exception {
        WorktreeService svc = service(2);
        svc.create(UUID.randomUUID());
        svc.create(UUID.randomUUID());

        assertThatThrownBy(() -> svc.create(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worktree cap reached");
    }

    @Test
    void gcStale_removes_non_uuid_directories_but_keeps_uuid_ones() throws Exception {
        WorktreeService svc = service(20);
        UUID legit = UUID.randomUUID();
        svc.create(legit);

        Path stray = worktreesRoot.resolve("not-a-uuid");
        Files.createDirectories(stray);
        Files.writeString(stray.resolve("garbage.txt"), "old content");

        int removed = svc.gcStale();

        assertThat(removed).isEqualTo(1);
        assertThat(stray).doesNotExist();
        assertThat(worktreesRoot.resolve(legit.toString())).exists();
    }

    @Test
    void create_branches_from_origin_not_stale_local_main() throws Exception {
        // Set up a bare origin and a local clone whose `main` is intentionally
        // 1 commit behind origin/main. Verifies the load-bearing fix: a worktree
        // created via the service must include the upstream commit even though
        // the local main ref doesn't see it yet.
        Path origin = tempDir.resolve("origin.git");
        Files.createDirectories(origin);
        run(origin, "git", "init", "-q", "--bare", "-b", "main");

        // Seed origin with an initial v1 commit pushed from a scratch tree.
        Path remoteWork = tempDir.resolve("remote-work");
        Files.createDirectories(remoteWork);
        run(remoteWork, "git", "init", "-q", "-b", "main");
        run(remoteWork, "git", "config", "user.email", "remote@example.com");
        run(remoteWork, "git", "config", "user.name", "Remote");
        run(remoteWork, "git", "remote", "add", "origin", origin.toString());
        Files.writeString(remoteWork.resolve("README.md"), "v1\n");
        run(remoteWork, "git", "add", ".");
        run(remoteWork, "git", "commit", "-q", "-m", "v1");
        run(remoteWork, "git", "push", "-q", "origin", "main");

        // Local clone: catches up to origin/main with the v1 commit.
        Path localClone = tempDir.resolve("local-clone");
        Files.createDirectories(localClone);
        run(localClone, "git", "clone", "-q", "-b", "main", origin.toString(), ".");
        run(localClone, "git", "config", "user.email", "test@example.com");
        run(localClone, "git", "config", "user.name", "Test");

        // Upstream advances with a v2 commit. Local main stays at v1 (no pull).
        Files.writeString(remoteWork.resolve("NEW.md"), "added upstream\n");
        run(remoteWork, "git", "add", ".");
        run(remoteWork, "git", "commit", "-q", "-m", "v2 upstream-only");
        run(remoteWork, "git", "push", "-q", "origin", "main");

        WorktreeProperties props = new WorktreeProperties(
                worktreesRoot.toString(), localClone.toString(), "main", 20);
        WorktreeService svc = new WorktreeService(props);
        Files.createDirectories(worktreesRoot);

        UUID taskId = UUID.randomUUID();
        Path wt = svc.create(taskId);

        // The new worktree must contain the upstream-only file. If the service
        // had branched off local `main`, NEW.md would be missing.
        assertThat(wt.resolve("NEW.md")).exists();
        assertThat(wt.resolve("README.md")).exists();
    }

    @Test
    void create_refuses_if_target_already_exists_on_disk() throws Exception {
        WorktreeService svc = service(20);
        UUID taskId = UUID.randomUUID();
        Files.createDirectories(worktreesRoot.resolve(taskId.toString()));

        assertThatThrownBy(() -> svc.create(taskId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    private static void run(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        if (p.waitFor() != 0) {
            String out = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out);
        }
    }

    private static String capture(Path cwd, String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        return out;
    }
}
