package com.ai.orange.run;

import com.ai.orange.harness.FakeClaude;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEvent;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jooq.JSONB;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Shared wiring for the run ITs: fake Claude Code and a temp base repo with worktrees (the loops are off in tests). */
public final class RunTestSupport {

    private RunTestSupport() {}

    public static Path tempDir(String prefix) {
        try {
            return Files.createTempDirectory(prefix);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A git repo on {@code main} with one commit, the base the task worktrees branch from. */
    public static Path baseRepo() {
        Path repo = tempDir("orange-base-");
        try {
            Files.writeString(repo.resolve("README.md"), "# demo\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "t@e");
        git(repo, "config", "user.name", "T");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "initial");
        return repo;
    }

    public static void register(DynamicPropertyRegistry r, Path runRoot, Path baseRepo, Path worktreeRoot) {
        FakeClaude.register(r, runRoot);
        r.add("orange.worktrees.base-repo", baseRepo::toString);
        r.add("orange.worktrees.root", worktreeRoot::toString);
        r.add("orange.worktrees.base-branch", () -> "main");
    }

    /** A task already moved to RUNNING, the way the reactor leaves it when it queues an implement run. */
    public static Task runningTask(TaskService taskService, TaskRepository tasks, String description) {
        Task t = taskService.createGraph(List.of(new TaskDef("run-it-" + UUID.randomUUID(), "Add a greeting",
                description, "implementer", null, null)), List.of()).get(0);
        tasks.transitionStatus(t.id(), TaskStatus.READY, TaskStatus.RUNNING);
        return tasks.findById(t.id()).orElseThrow();
    }

    /**
     * A task whose implement run is done: its worktree has one commit adding {@code file}, its head is
     * recorded, and it is VERIFYING, the way the post-run step leaves it.
     */
    public static Task verifyingTask(TaskService taskService, TaskRepository tasks,
                                     com.ai.orange.worktree.WorktreeService worktrees, String description,
                                     String file, String content) {
        Task t = runningTask(taskService, tasks, description);
        Path wt;
        try {
            wt = worktrees.ensure(t.id());
            Path target = wt.resolve(file);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        git(wt, "add", "-A");
        git(wt, "-c", "user.email=t@e", "-c", "user.name=T", "commit", "-q", "-m", "task: " + file);
        tasks.recordHead(t.id(), "orange/" + t.id(), git(wt, "rev-parse", "HEAD"));
        tasks.transitionStatus(t.id(), TaskStatus.RUNNING, TaskStatus.VERIFYING);
        return tasks.findById(t.id()).orElseThrow();
    }

    public static String headInput(String headSha) {
        return "{\"head_sha\":\"" + headSha + "\"}";
    }

    public static Run start(RunRepository runs, RunsProperties props, UUID taskId, RunKind kind, String role,
                            String inputJson) {
        return runs.insertRunning(kind, role, taskId, props.workerId(), props.leaseTtl(), JSONB.valueOf(inputJson));
    }

    public static List<String> runEventTypes(RunEventRepository events, UUID runId) {
        return events.findByRunSince(runId, 0, 500).stream().map(RunEventRow::type).toList();
    }

    public static String runEventsText(RunEventRepository events, UUID runId) {
        return events.findByRunSince(runId, 0, 500).stream()
                .map(e -> e.type() + " " + e.payload().data())
                .collect(Collectors.joining("\n"));
    }

    public static List<String> taskEventTypes(TaskEventRepository events, UUID taskId) {
        return events.findByTaskSince(taskId, 0, 500).stream().map(TaskEvent::eventType).toList();
    }

    public static String git(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (p.waitFor() != 0) throw new IllegalStateException(String.join(" ", cmd) + ": " + out);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
