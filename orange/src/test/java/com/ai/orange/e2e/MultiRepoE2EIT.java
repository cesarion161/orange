package com.ai.orange.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.json.Json;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceService;
import com.ai.orange.project.TaskPlacement;
import com.ai.orange.repo.FakeGithub;
import com.ai.orange.repo.Mirrors;
import com.ai.orange.repo.Repo;
import com.ai.orange.repo.RepoKey;
import com.ai.orange.run.RunTestSupport;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.Git;
import com.ai.orange.worktree.WorktreeService;
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
 * The R2 exit (remote-first spec §8): two projects in two repos get task work integrated and
 * pushed by one orange, each from its own mirror, with nothing driven by hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class MultiRepoE2EIT {

    static final FakeGithub GITHUB = new FakeGithub(RunTestSupport.tempDir("multi-gh-"));
    static final Path MIRRORS = RunTestSupport.tempDir("multi-mirrors-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        // The legacy base repo stays for tasks without a project; these tasks never use it.
        RunTestSupport.register(r, RunTestSupport.tempDir("multi-runs-"), RunTestSupport.baseRepo(),
                RunTestSupport.tempDir("multi-wt-"));
        r.add("orange.runs.loop-enabled", () -> "true");
        r.add("orange.events.processing-enabled", () -> "true");
        r.add("orange.runs.reactor-sweep", () -> "1s");
        r.add("orange.runs.scheduler-poll", () -> "1s");
        r.add("orange.repos.root", MIRRORS::toString);
        r.add("orange.repos.github-url", GITHUB::url);
    }

    @Autowired ProjectService projects;
    @Autowired SpaceService space;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired WorktreeService worktrees;
    @Autowired Mirrors mirrors;
    @Autowired AttentionRepository attention;
    @Autowired SchedulerPause pause;

    @AfterEach
    void resume() {
        pause.resume();
    }

    record Placed(Project project, Repo repo) {}

    Placed project(String name, boolean withEvidence) throws Exception {
        String unique = name + "-" + UUID.randomUUID().toString().substring(0, 8);
        RepoKey key = GITHUB.create("me/" + unique);
        Project p = projects.create(unique, null);
        Repo repo = projects.addGithubRepo(p.id(), key);
        if (withEvidence) {
            space.setConvention(p.id(), null, "evidence", Json.mapper().readTree("[\"test -f orange-fake.txt\"]"),
                    SpaceService.Author.USER);
        }
        return new Placed(p, repo);
    }

    Task task(Placed placed, String title) {
        return taskService.createGraph(List.of(new TaskDef("t-" + UUID.randomUUID(), title, "Make orange-fake.txt.",
                "implementer", null, null, new TaskPlacement(placed.project().id(), placed.repo().id()))), List.of()).get(0);
    }

    Task fresh(Task t) {
        return tasks.findById(t.id()).orElseThrow();
    }

    @Test
    void twoProjectsInTwoReposAreIntegratedAndPushedByOneOrange() throws Exception {
        Placed alpha = project("alpha", true);
        Placed beta = project("beta", true);
        Task a = task(alpha, "Alpha work");
        Task b = task(beta, "Beta work");

        RunLoopE2EIT.await("both tasks are integrated", () -> fresh(a).status() == TaskStatus.INTEGRATED
                && fresh(b).status() == TaskStatus.INTEGRATED);

        Task doneA = fresh(a);
        Task doneB = fresh(b);
        assertThat(doneA.projectId()).isEqualTo(alpha.project().id());
        assertThat(doneB.repoId()).isEqualTo(beta.repo().id());
        RepoKey ka = alpha.repo().key();
        RepoKey kb = beta.repo().key();
        assertThat(GITHUB.head(ka, "main")).isEqualTo(doneA.headSha());
        assertThat(GITHUB.head(kb, "main")).isEqualTo(doneB.headSha());
        // Each repo got its own work and nothing of the other's.
        assertThat(Git.exitCode(GITHUB.bare(kb), Git.QUICK, "cat-file", "-e", doneA.headSha() + "^{commit}")).isNotZero();
        assertThat(Git.exitCode(GITHUB.bare(ka), Git.QUICK, "cat-file", "-e", doneB.headSha() + "^{commit}")).isNotZero();
        // Their worktrees came from their own mirrors.
        assertThat(worktrees.base(a.id()).repo()).isEqualTo(mirrors.path(ka));
        assertThat(worktrees.base(b.id()).repo()).isEqualTo(mirrors.path(kb));
    }

    @Test
    void aProjectTaskWithoutEvidenceWaitsAndSaysSo() throws Exception {
        Placed gamma = project("gamma", false);
        Task t = task(gamma, "Gamma work");

        RunLoopE2EIT.await("the task is implemented and waits for evidence", () -> fresh(t).status() == TaskStatus.VERIFYING
                && attention.findByKey("project:" + gamma.project().id() + ":evidence_missing").isPresent());
        Thread.sleep(2_500); // a couple of sweeps: it keeps waiting, it does not fail or move on
        assertThat(fresh(t).status()).isEqualTo(TaskStatus.VERIFYING);
        AttentionItem item = attention.findByKey("project:" + gamma.project().id() + ":evidence_missing").orElseThrow();
        assertThat(item.title()).contains(gamma.project().name());

        space.setConvention(gamma.project().id(), null, "evidence", Json.mapper().readTree("[\"test -f orange-fake.txt\"]"),
                SpaceService.Author.USER);

        RunLoopE2EIT.await("the task is integrated once evidence is in force", () -> fresh(t).status() == TaskStatus.INTEGRATED);
        assertThat(GITHUB.head(gamma.repo().key(), "main")).isEqualTo(fresh(t).headSha());
    }
}
