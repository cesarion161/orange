package com.ai.orange.run;

import static com.ai.orange.run.RunTestSupport.git;
import static com.ai.orange.run.RunTestSupport.headInput;
import static com.ai.orange.run.RunTestSupport.start;
import static com.ai.orange.run.RunTestSupport.taskEventTypes;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.task.EdgeKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeProperties;
import com.ai.orange.worktree.WorktreeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Merge-mode integration: rebase onto the base, re-verify if that changed the head, otherwise
 * fast-forward the base and push it. Each test gets its own bare origin and base checkout.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class IntegratorIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("integrator-it-");
    static final Path SPRING_BASE = RunTestSupport.baseRepo();
    static final Path SPRING_WORKTREES = RunTestSupport.tempDir("integrator-it-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, SPRING_BASE, SPRING_WORKTREES);
    }

    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired EvidenceRepository evidence;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository taskEvents;
    @Autowired RunLifecycle lifecycle;

    @TempDir Path tmp;
    Path origin;
    Path base;
    WorktreeService worktrees;
    Integrator integrator;

    @BeforeEach
    void repos() throws Exception {
        origin = tmp.resolve("origin.git");
        git(tmp, "init", "-q", "--bare", "-b", "main", origin.toString());
        base = newRepo("base");
        git(base, "remote", "add", "origin", origin.toString());
        git(base, "push", "-q", "-u", "origin", "main");
        use(base, true);
    }

    Path newRepo(String name) throws Exception {
        Path repo = tmp.resolve(name);
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.email", "t@e");
        git(repo, "config", "user.name", "T");
        Files.writeString(repo.resolve("README.md"), "# demo\n");
        git(repo, "add", ".");
        git(repo, "commit", "-q", "-m", "initial");
        return repo;
    }

    void use(Path baseRepo, boolean remotePush) {
        worktrees = new WorktreeService(new WorktreeProperties(tmp.resolve("worktrees-" + baseRepo.getFileName()).toString(),
                baseRepo.toString(), "main", 20));
        integrator = new Integrator(runs, worktrees, new IntegrationProperties(remotePush), props, lifecycle);
    }

    Task newTask() {
        return taskService.createGraph(List.of(new TaskDef("int-" + UUID.randomUUID(), "Integrate me", "x",
                "implementer", null, null)), List.of()).get(0);
    }

    /** {@code task} implemented (one commit writing {@code file}), verified, at the recorded head. */
    Task verified(Task task, String file, String content) throws Exception {
        tasks.transitionStatus(task.id(), TaskStatus.READY, TaskStatus.RUNNING);
        Path wt = worktrees.ensure(task.id());
        Files.writeString(wt.resolve(file), content);
        git(wt, "add", "-A");
        git(wt, "-c", "user.email=t@e", "-c", "user.name=T", "commit", "-q", "-m", "task: " + file);
        tasks.recordHead(task.id(), "orange/" + task.id(), git(wt, "rev-parse", "HEAD"));
        tasks.transitionStatus(task.id(), TaskStatus.RUNNING, TaskStatus.VERIFYING);
        tasks.transitionStatus(task.id(), TaskStatus.VERIFYING, TaskStatus.VERIFIED);
        return tasks.findById(task.id()).orElseThrow();
    }

    RunResult integrate(Task task) {
        return integrator.execute(start(runs, props, task.id(), RunKind.INTEGRATE, "integrator", headInput(task.headSha())));
    }

    /** Lands a commit on origin's main from a second clone, as another developer would. */
    void commitOnOrigin(String file, String content) throws Exception {
        Path other = tmp.resolve("other-" + UUID.randomUUID());
        git(tmp, "clone", "-q", origin.toString(), other.toString());
        git(other, "config", "user.email", "o@e");
        git(other, "config", "user.name", "O");
        Files.writeString(other.resolve(file), content);
        git(other, "add", "-A");
        git(other, "commit", "-q", "-m", "other: " + file);
        git(other, "push", "-q", "origin", "main");
    }

    String originMain() {
        return git(origin, "rev-parse", "refs/heads/main");
    }

    @Test
    void fastForwardsAndPushes() throws Exception {
        String p = "p-" + UUID.randomUUID();
        String c = "c-" + UUID.randomUUID();
        List<Task> graph = taskService.createGraph(List.of(
                new TaskDef(p, "Parent", "x", "implementer", null, null),
                new TaskDef(c, "Child", "y", "implementer", null, null)), List.of(new EdgeKey(p, c)));
        Task parent = graph.get(0);
        Task child = graph.get(1);
        assertThat(child.status()).isEqualTo(TaskStatus.PENDING);
        Task task = verified(parent, "a.txt", "a\n");

        RunResult result = integrate(task);

        assertThat(result.status()).as(String.valueOf(result.reason())).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outcome()).isEqualTo(new RunOutcome.Integration("integrated", task.headSha(), true,
                "updated", List.of(), null));
        assertThat(originMain()).isEqualTo(task.headSha());
        assertThat(git(base, "rev-parse", "main")).as("the clean local checkout moved too").isEqualTo(task.headSha());
        assertThat(base.resolve("a.txt")).exists();
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.INTEGRATED);
        assertThat(tasks.findById(child.id()).orElseThrow().status()).isEqualTo(TaskStatus.READY);
        assertThat(taskEventTypes(taskEvents, task.id())).contains("integrated");
    }

    @Test
    void baseMovedSinceVerificationRebasesAndReverifies() throws Exception {
        Task task = verified(newTask(), "a.txt", "a\n");
        evidence.record(task.id(), null, task.headSha(), "diff_review", null, null, "Looks good", true);
        commitOnOrigin("b.txt", "b\n");
        String otherHead = originMain();

        RunResult result = integrate(task);

        RunOutcome.Integration outcome = (RunOutcome.Integration) result.outcome();
        assertThat(outcome.status()).isEqualTo("rebased");
        assertThat(outcome.headSha()).isNotEqualTo(task.headSha());
        assertThat(originMain()).as("nothing lands before re-verification").isEqualTo(otherHead);
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.VERIFYING);
        assertThat(after.headSha()).isEqualTo(outcome.headSha());
        assertThat(evidence.hasPassingDiffReview(task.id(), outcome.headSha())).isTrue();
        assertThat(evidence.findByTaskAndHead(task.id(), outcome.headSha())).singleElement()
                .satisfies(e -> assertThat(e.outputTail())
                        .isEqualTo("carried over from " + task.headSha() + " after a clean rebase"));
        assertThat(taskEventTypes(taskEvents, task.id())).contains("rebased");
    }

    @Test
    void conflictSendsTheTaskBackWithoutCountingAFailure() throws Exception {
        Task task = verified(newTask(), "README.md", "# mine\n");
        commitOnOrigin("README.md", "# theirs\n");

        RunResult result = integrate(task);

        RunOutcome.Integration outcome = (RunOutcome.Integration) result.outcome();
        assertThat(outcome.status()).isEqualTo("conflict");
        assertThat(outcome.files()).containsExactly("README.md");
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(after.failures()).isZero();
        Path wt = worktrees.resolve(task.id());
        assertThat(git(wt, "status", "--porcelain")).isEmpty();
        assertThat(Path.of(git(wt, "rev-parse", "--path-format=absolute", "--git-path", "rebase-merge"))).doesNotExist();
        assertThat(git(wt, "rev-parse", "HEAD")).as("the branch is as it was").isEqualTo(task.headSha());
        assertThat(taskEventTypes(taskEvents, task.id())).contains("integration_conflict");
    }

    @Test
    void twoVerifiedTasksBothLand() throws Exception {
        Task first = verified(newTask(), "first.txt", "1\n");
        Task second = verified(newTask(), "second.txt", "2\n");

        assertThat(((RunOutcome.Integration) integrate(first).outcome()).status()).isEqualTo("integrated");
        RunOutcome.Integration rebased = (RunOutcome.Integration) integrate(second).outcome();
        assertThat(rebased.status()).isEqualTo("rebased");

        // Re-verification (the reactor's verify and review runs) passes:
        tasks.transitionStatus(second.id(), TaskStatus.VERIFYING, TaskStatus.VERIFIED);
        Task reverified = tasks.findById(second.id()).orElseThrow();
        assertThat(((RunOutcome.Integration) integrate(reverified).outcome()).status()).isEqualTo("integrated");

        assertThat(git(origin, "log", "--format=%s", "main")).contains("task: first.txt").contains("task: second.txt");
        assertThat(tasks.findById(first.id()).orElseThrow().status()).isEqualTo(TaskStatus.INTEGRATED);
        assertThat(tasks.findById(second.id()).orElseThrow().status()).isEqualTo(TaskStatus.INTEGRATED);
    }

    @Test
    void noRemoteUpdatesTheLocalBase() throws Exception {
        Path local = newRepo("local-only");
        use(local, true);
        Task task = verified(newTask(), "a.txt", "a\n");

        RunResult result = integrate(task);

        assertThat(result.outcome()).isEqualTo(new RunOutcome.Integration("integrated", task.headSha(), false,
                "updated", List.of(), null));
        assertThat(git(local, "rev-parse", "main")).isEqualTo(task.headSha());
        assertThat(local.resolve("a.txt")).exists();
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.INTEGRATED);
    }

    @Test
    void baseBranchNotCheckedOutIsMovedByRef() throws Exception {
        Task task = verified(newTask(), "a.txt", "a\n");
        git(base, "checkout", "-q", "-b", "elsewhere");

        RunResult result = integrate(task);

        assertThat(((RunOutcome.Integration) result.outcome()).localBase()).isEqualTo("updated");
        assertThat(git(base, "rev-parse", "main")).isEqualTo(task.headSha());
        assertThat(git(base, "rev-parse", "--abbrev-ref", "HEAD")).isEqualTo("elsewhere");
    }

    @Test
    void dirtyBaseCheckoutIsLeftAlone() throws Exception {
        String before = git(base, "rev-parse", "main");
        Task task = verified(newTask(), "a.txt", "a\n");
        Files.writeString(base.resolve("README.md"), "# edited by a person\n");

        RunResult result = integrate(task);

        assertThat(result.outcome()).isEqualTo(new RunOutcome.Integration("integrated", task.headSha(), true,
                "dirty", List.of(), null));
        assertThat(originMain()).isEqualTo(task.headSha());
        assertThat(git(base, "rev-parse", "main")).isEqualTo(before);
        assertThat(Files.readString(base.resolve("README.md"))).isEqualTo("# edited by a person\n");
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.INTEGRATED);
    }

    @Test
    void dirtyBaseWithoutARemoteIsNotIntegrated() throws Exception {
        Path local = newRepo("local-dirty");
        use(local, true);
        Task task = verified(newTask(), "a.txt", "a\n");
        Files.writeString(local.resolve("README.md"), "# edited by a person\n");

        RunResult result = integrate(task);

        assertThat(result.reason()).isEqualTo("integrate_failed");
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.NEEDS_HUMAN);
        assertThat(after.failures()).as("not the model's fault").isZero();
        assertThat(git(local, "rev-parse", "main")).isNotEqualTo(task.headSha());
    }
}
