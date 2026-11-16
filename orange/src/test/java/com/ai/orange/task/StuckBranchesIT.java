package com.ai.orange.task;

import static com.ai.orange.run.RunTestSupport.git;
import static com.ai.orange.run.RunTestSupport.headInput;
import static com.ai.orange.run.RunTestSupport.start;
import static com.ai.orange.run.RunTestSupport.taskEventTypes;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.json.Json;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceService;
import com.ai.orange.project.TaskPlacement;
import com.ai.orange.repo.FakeGithub;
import com.ai.orange.repo.Repo;
import com.ai.orange.repo.RepoKey;
import com.ai.orange.run.Integrator;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunResult;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunTestSupport;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.run.TaskCancellation;
import com.ai.orange.run.WorkerRunner;
import com.ai.orange.worktree.WorktreeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A stuck task's branch on GitHub (builder's call 13): pushed as {@code orange/task/<short id>} so the
 * user can look at it from the laptop, named in the task's item, and deleted once the task is
 * integrated or cancelled. A directory of bare repositories stands in for GitHub.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class StuckBranchesIT {

    static final FakeGithub GITHUB = new FakeGithub(RunTestSupport.tempDir("stuck-gh-"));

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RunTestSupport.tempDir("stuck-runs-"), RunTestSupport.baseRepo(),
                RunTestSupport.tempDir("stuck-wt-"));
        r.add("orange.repos.root", () -> RunTestSupport.tempDir("stuck-mirrors-").toString());
        r.add("orange.repos.github-url", GITHUB::url);
    }

    @Autowired ProjectService projects;
    @Autowired SpaceService space;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository taskEvents;
    @Autowired WorktreeService worktrees;
    @Autowired WorkerRunner runner;
    @Autowired Integrator integrator;
    @Autowired TaskCancellation cancellation;
    @Autowired RunRepository runs;
    @Autowired RunsProperties props;
    @Autowired AttentionService attention;
    @Autowired AttentionRepository items;
    @Autowired SchedulerPause pause;

    @AfterEach
    void resume() {
        pause.resume();
    }

    record Placed(Project project, Repo repo) {}

    Placed project() throws Exception {
        String name = "stuck-" + UUID.randomUUID().toString().substring(0, 8);
        RepoKey key = GITHUB.create("me/" + name);
        Project p = projects.create(name, null);
        Repo repo = projects.addGithubRepo(p.id(), key);
        space.setConvention(p.id(), null, "evidence", Json.mapper().readTree("[\"true\"]"), SpaceService.Author.USER);
        return new Placed(p, repo);
    }

    /** A running task of the project whose worktree has one commit; its next run asks a person. */
    Task stuckTask(Placed placed) throws Exception {
        Task t = taskService.createGraph(List.of(new TaskDef("t-" + UUID.randomUUID(), "Pick a database",
                "[[fake:blocked]]", "implementer", null, null,
                new TaskPlacement(placed.project().id(), placed.repo().id()))), List.of()).get(0);
        tasks.transitionStatus(t.id(), TaskStatus.READY, TaskStatus.RUNNING);
        Path wt = worktrees.ensure(t.id());
        Files.writeString(wt.resolve("schema.sql"), "-- which database?\n");
        git(wt, "add", "-A");
        git(wt, "-c", "user.email=t@e", "-c", "user.name=T", "commit", "-q", "-m", "a first schema");
        RunResult r = runner.execute(start(runs, props, t.id(), RunKind.IMPLEMENT, "implementer", "{}"));
        assertThat(r.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(tasks.findById(t.id()).orElseThrow().status()).isEqualTo(TaskStatus.NEEDS_HUMAN);
        return tasks.findById(t.id()).orElseThrow();
    }

    static String branch(Task t) {
        return "orange/task/" + Task.shortId(t.id());
    }

    AttentionItem itemOf(Task t) {
        return items.find(null, 200).stream().filter(i -> t.id().equals(i.taskId())).findFirst().orElseThrow();
    }

    @Test
    void aStuckTaskBranchIsPushedAndNamedInItsItem() throws Exception {
        Placed placed = project();
        Task t = stuckTask(placed);

        String head = git(worktrees.resolve(t.id()), "rev-parse", "HEAD");
        assertThat(GITHUB.head(placed.repo().key(), branch(t))).isEqualTo(head);
        assertThat(branch(t)).hasSize("orange/task/".length() + 8).endsWith(t.id().toString().substring(28));
        assertThat(taskEventTypes(taskEvents, t.id())).contains("branch_pushed");
        assertThat(itemOf(t).body()).contains(branch(t));
    }

    @Test
    void integratingDeletesIt() throws Exception {
        Placed placed = project();
        Task t = stuckTask(placed);
        assertThat(GITHUB.head(placed.repo().key(), branch(t))).isNotNull();

        attention.resolve(itemOf(t).id(), "Postgres.", "user");
        Path wt = worktrees.resolve(t.id());
        String head = git(wt, "rev-parse", "HEAD");
        tasks.recordHead(t.id(), "orange/" + t.id(), head);
        tasks.transitionStatus(t.id(), TaskStatus.READY, TaskStatus.RUNNING);
        tasks.transitionStatus(t.id(), TaskStatus.RUNNING, TaskStatus.VERIFYING);
        tasks.transitionStatus(t.id(), TaskStatus.VERIFYING, TaskStatus.VERIFIED);

        RunResult r = integrator.execute(start(runs, props, t.id(), RunKind.INTEGRATE, "orange", headInput(head)));

        assertThat(r.status()).as(String.valueOf(r.reason())).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(tasks.findById(t.id()).orElseThrow().status()).isEqualTo(TaskStatus.INTEGRATED);
        assertThat(GITHUB.head(placed.repo().key(), "main")).isEqualTo(head);
        assertThat(GITHUB.head(placed.repo().key(), branch(t))).isNull();
        assertThat(taskEventTypes(taskEvents, t.id())).contains("branch_deleted");
    }

    @Test
    void cancellingDeletesIt() throws Exception {
        Placed placed = project();
        Task t = stuckTask(placed);
        assertThat(GITHUB.head(placed.repo().key(), branch(t))).isNotNull();

        cancellation.cancel(t.id());

        assertThat(GITHUB.head(placed.repo().key(), branch(t))).isNull();
        assertThat(itemOf(t).status()).isEqualTo(AttentionItem.RESOLVED);
    }

    @Test
    void cancellingATaskWhoseBranchWasNeverPushedTouchesNothing() throws Exception {
        Placed placed = project();
        Task t = taskService.createGraph(List.of(new TaskDef("t-" + UUID.randomUUID(), "Never stuck", "x",
                "implementer", null, null, new TaskPlacement(placed.project().id(), placed.repo().id()))),
                List.of()).get(0);

        cancellation.cancel(t.id());

        assertThat(taskEventTypes(taskEvents, t.id())).doesNotContain("branch_pushed", "branch_deleted");
    }

    @Test
    void noRemotePushNoBranch() throws Exception {
        Placed placed = project();
        space.setConvention(placed.project().id(), null, "remote-push", Json.mapper().readTree("false"),
                SpaceService.Author.USER);

        Task t = stuckTask(placed);

        assertThat(GITHUB.head(placed.repo().key(), branch(t))).isNull();
        assertThat(taskEventTypes(taskEvents, t.id())).doesNotContain("branch_pushed");
        AttentionItem item = itemOf(t);
        assertThat(item.urgency()).isEqualTo(AttentionItem.URGENT);
        assertThat(item.body()).doesNotContain("orange/task/");
    }

    @Test
    void aFailedPushStillFilesTheItem() throws Exception {
        Placed placed = project();
        // GitHub refuses every push to this repo: the branch cannot land, the item must still be filed.
        Path hook = GITHUB.bare(placed.repo().key()).resolve("hooks/pre-receive");
        Files.writeString(hook, "#!/bin/sh\necho refused >&2\nexit 1\n");
        hook.toFile().setExecutable(true);

        Task t = stuckTask(placed);

        assertThat(GITHUB.head(placed.repo().key(), branch(t))).isNull();
        assertThat(taskEventTypes(taskEvents, t.id())).doesNotContain("branch_pushed");
        AttentionItem item = itemOf(t);
        assertThat(item.status()).isEqualTo(AttentionItem.OPEN);
        assertThat(item.body()).contains("Which database should I use").doesNotContain("orange/task/");
    }
}
