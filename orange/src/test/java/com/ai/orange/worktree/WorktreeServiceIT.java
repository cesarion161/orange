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
                worktreesRoot.toString(), baseRepo.toString(), maxCount);
        WorktreeService svc = new WorktreeService(props);
        // Manually invoke the @PostConstruct since we're not in a Spring context.
        Files.createDirectories(worktreesRoot);
        return svc;
    }

    @Test
    void create_then_remove_round_trip() throws Exception {
        WorktreeService svc = service(20);
        UUID taskId = UUID.randomUUID();

        Path worktree = svc.create(taskId, "main");
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
    void create_refuses_when_max_count_reached() throws Exception {
        WorktreeService svc = service(2);
        svc.create(UUID.randomUUID(), "main");
        svc.create(UUID.randomUUID(), "main");

        assertThatThrownBy(() -> svc.create(UUID.randomUUID(), "main"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("worktree cap reached");
    }

    @Test
    void gcStale_removes_non_uuid_directories_but_keeps_uuid_ones() throws Exception {
        WorktreeService svc = service(20);
        UUID legit = UUID.randomUUID();
        svc.create(legit, "main");

        Path stray = worktreesRoot.resolve("not-a-uuid");
        Files.createDirectories(stray);
        Files.writeString(stray.resolve("garbage.txt"), "old content");

        int removed = svc.gcStale();

        assertThat(removed).isEqualTo(1);
        assertThat(stray).doesNotExist();
        assertThat(worktreesRoot.resolve(legit.toString())).exists();
    }

    @Test
    void create_refuses_if_target_already_exists_on_disk() throws Exception {
        WorktreeService svc = service(20);
        UUID taskId = UUID.randomUUID();
        Files.createDirectories(worktreesRoot.resolve(taskId.toString()));

        assertThatThrownBy(() -> svc.create(taskId, "main"))
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
