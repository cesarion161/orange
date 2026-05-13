package com.ai.orange.github;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.EdgeKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Drives {@link GithubPrService} end-to-end against a stubbed
 * {@link GithubClient} and a real ephemeral git remote, then verifies the PR
 * branch was actually pushed and {@code tasks.metadata} got stamped.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, GithubPrServiceIT.StubGithubClientConfig.class})
@EnabledIf("isGitAvailable")
class GithubPrServiceIT {

    static AtomicReference<PrInfo> stubResult = new AtomicReference<>(
            new PrInfo("orange/test", 11, "https://github.com/orange/test/pull/11"));

    @TempDir Path temp;

    @Autowired GithubPrService prService;
    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepo;
    @Autowired DSLContext dsl;

    @DynamicPropertySource
    static void disableAutoStart(DynamicPropertyRegistry r) {
        r.add("orange.workflow.auto-start", () -> "false");
    }

    static boolean isGitAvailable() {
        try {
            return new ProcessBuilder("git", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void push_conflict_enqueues_a_triage_task_then_rethrows() throws Exception {
        Path bareRemote = temp.resolve("conflict-remote.git");
        Path worktreeA = temp.resolve("wtA");
        Path worktreeB = temp.resolve("wtB");
        run(temp, "git", "init", "--bare", "-b", "main", bareRemote.toString());

        TaskDef def = new TaskDef("conflict-it-" + UUID.randomUUID(),
                "PR with conflicting push", null, "dev", "dev_only", null, null);
        Task task = taskService.createGraph(List.of(def), List.<EdgeKey>of()).get(0);
        String branch = "orange/" + task.id();

        // Worktree A pushes the branch first.
        setupWorktree(worktreeA, bareRemote);
        run(worktreeA, "git", "checkout", "-q", "-b", branch);
        Files.writeString(worktreeA.resolve("a.txt"), "from A");
        run(worktreeA, "git", "add", ".");
        run(worktreeA, "git", "commit", "-q", "-m", "A");
        run(worktreeA, "git", "push", "-u", "origin", branch);

        // Worktree B creates the same branch name with diverged history, then
        // openPrForTask attempts to push it → non-fast-forward rejection.
        setupWorktree(worktreeB, bareRemote);
        run(worktreeB, "git", "checkout", "-q", "-b", branch);
        Files.writeString(worktreeB.resolve("b.txt"), "from B");
        run(worktreeB, "git", "add", ".");
        run(worktreeB, "git", "commit", "-q", "-m", "B");

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                prService.openPrForTask(task.id(), worktreeB))
                .isInstanceOf(GithubPrService.PushConflictException.class);

        int triageCount = dsl.fetchCount(TASKS, TASKS.ROLE.eq("triage"));
        assertThat(triageCount).isEqualTo(1);
    }

    private void setupWorktree(Path wt, Path bareRemote) throws Exception {
        Files.createDirectories(wt);
        run(wt, "git", "init", "-q", "-b", "main");
        run(wt, "git", "config", "user.email", "t@e");
        run(wt, "git", "config", "user.name", "T");
        run(wt, "git", "remote", "add", "origin", bareRemote.toString());
        Files.writeString(wt.resolve("README.md"), "hi");
        run(wt, "git", "add", ".");
        run(wt, "git", "commit", "-q", "-m", "init");
        // Push main so the remote has it; needed for the branch base.
        try { run(wt, "git", "push", "-u", "origin", "main"); }
        catch (Exception ignored) { /* second worktree's push is a no-op */ }
    }

    @Test
    void openPrForTask_pushes_branch_and_stamps_metadata() throws Exception {
        Path bareRemote = temp.resolve("remote.git");
        Path worktree = temp.resolve("wt");
        run(temp, "git", "init", "--bare", "-b", "main", bareRemote.toString());
        Files.createDirectories(worktree);
        run(worktree, "git", "init", "-q", "-b", "main");
        run(worktree, "git", "config", "user.email", "t@e");
        run(worktree, "git", "config", "user.name", "T");
        run(worktree, "git", "remote", "add", "origin", bareRemote.toString());
        Files.writeString(worktree.resolve("README.md"), "hi");
        run(worktree, "git", "add", ".");
        run(worktree, "git", "commit", "-q", "-m", "initial");

        // Branch must be named the same as WorktreeService produces: orange/<taskId>.
        TaskDef def = new TaskDef("pr-it-" + UUID.randomUUID(),
                "Open a PR", "Body of the PR description.",
                "dev", "dev_only", null, null);
        Task task = taskService.createGraph(List.of(def), List.<EdgeKey>of()).get(0);

        run(worktree, "git", "checkout", "-q", "-b", "orange/" + task.id());
        Files.writeString(worktree.resolve("change.txt"), "feature work");
        run(worktree, "git", "add", ".");
        run(worktree, "git", "commit", "-q", "-m", "wip");

        stubResult.set(new PrInfo("orange/test", 99, "https://github.com/orange/test/pull/99"));

        PrInfo info = prService.openPrForTask(task.id(), worktree);

        assertThat(info.number()).isEqualTo(99);
        assertThat(info.repo()).isEqualTo("orange/test");

        // Branch landed on the bare remote.
        String branches = capture(bareRemote, "git", "branch", "--list", "orange/" + task.id());
        assertThat(branches).contains("orange/" + task.id());

        // Metadata got stamped with PR fields.
        Task reloaded = taskRepo.findById(task.id()).orElseThrow();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode meta = mapper.readTree(reloaded.metadata().data());
        assertThat(meta.get("pr_number").asInt()).isEqualTo(99);
        assertThat(meta.get("pr_url").asText()).contains("/pull/99");
        assertThat(meta.get("pr_repo").asText()).isEqualTo("orange/test");

        // findByPullRequest now finds it.
        assertThat(taskRepo.findByPullRequest("orange/test", 99))
                .map(Task::id).hasValue(task.id());
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

    /**
     * Replaces {@link KohsukeGithubClient} with a stub that returns
     * {@link #stubResult} verbatim. Avoids hitting the real GitHub API and
     * makes the PR number/URL deterministic for assertions.
     */
    @TestConfiguration
    static class StubGithubClientConfig {
        @Bean
        @Primary
        GithubClient stubGithubClient() {
            return (title, body, head, base, reviewers) -> {
                // Sanity assertions on what we'd actually send to GitHub.
                if (title == null || title.isBlank()) throw new IOException("empty title");
                if (head == null || !head.startsWith("orange/")) throw new IOException("unexpected head: " + head);
                return stubResult.get();
            };
        }
    }
}
