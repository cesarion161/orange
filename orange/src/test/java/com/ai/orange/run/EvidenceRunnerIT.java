package com.ai.orange.run;

import static com.ai.orange.run.RunTestSupport.headInput;
import static com.ai.orange.run.RunTestSupport.start;
import static com.ai.orange.run.RunTestSupport.taskEventTypes;
import static com.ai.orange.run.RunTestSupport.verifyingTask;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.evidence.Evidence;
import com.ai.orange.evidence.EvidenceProperties;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.worktree.WorktreeService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** orange runs the evidence commands itself, in the task's worktree, and turns failures into gaps. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EvidenceRunnerIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("evidence-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("evidence-it-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
    }

    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired EvidenceRepository evidence;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository taskEvents;
    @Autowired WorktreeService worktrees;
    @Autowired HarnessProperties harnessProps;
    @Autowired RunLifecycle lifecycle;

    EvidenceRunner runner(EvidenceProperties.Command... commands) {
        return runner(System.getenv(), commands);
    }

    EvidenceRunner runner(Map<String, String> hostEnv, EvidenceProperties.Command... commands) {
        return new EvidenceRunner(runs, evidence, worktrees, new EvidenceProperties(List.of(commands), 4000),
                harnessProps, props, lifecycle, () -> hostEnv);
    }

    static EvidenceProperties.Command command(String kind, String command) {
        return new EvidenceProperties.Command(kind, command, Duration.ofSeconds(60));
    }

    Task task() {
        return verifyingTask(taskService, tasks, worktrees, "Print hello.", "hello.txt", "hello\n");
    }

    RunResult verify(EvidenceRunner runner, Task task) {
        return runner.execute(start(runs, props, task.id(), RunKind.VERIFY, "verifier", headInput(task.headSha())));
    }

    @Test
    void passingCommandsRecordEvidence() {
        Task task = task();

        RunResult result = verify(runner(command("tests", "test -f hello.txt"), command("build", "echo built")), task);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outcome()).isEqualTo(new RunOutcome.Verification(false, true, List.of()));
        List<Evidence> rows = evidence.findByTaskAndHead(task.id(), task.headSha());
        assertThat(rows).extracting(Evidence::kind).containsExactlyInAnyOrder("tests", "build");
        assertThat(rows).allSatisfy(e -> {
            assertThat(e.passed()).isTrue();
            assertThat(e.exitCode()).isZero();
            assertThat(e.runId()).isEqualTo(result.runId());
        });
        assertThat(rows).filteredOn(e -> e.kind().equals("build")).singleElement()
                .satisfies(e -> assertThat(e.outputTail()).contains("built"));
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.VERIFYING);
        assertThat(after.failures()).isZero();
    }

    @Test
    void failingCommandIsAGapThatCounts() {
        Task task = task();
        // 10,000 characters of noise, then the line that matters: the tail keeps the end.
        String failing = "head -c 10000 /dev/zero | tr '\\0' x; echo; echo 'FooTest > bar FAILED'; exit 3";

        RunResult result = verify(runner(command("tests", failing)), task);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        RunOutcome.Verification v = (RunOutcome.Verification) result.outcome();
        assertThat(v.passed()).isFalse();
        assertThat(v.gaps()).singleElement().satisfies(g -> {
            assertThat(g.source()).isEqualTo("tests");
            assertThat(g.command()).isEqualTo(failing);
            assertThat(g.exitCode()).isEqualTo(3);
            assertThat(g.outputTail()).endsWith("FooTest > bar FAILED\n").hasSizeLessThanOrEqualTo(4000);
        });
        Evidence row = evidence.findByTaskAndHead(task.id(), task.headSha()).getFirst();
        assertThat(row.passed()).isFalse();
        assertThat(row.exitCode()).isEqualTo(3);
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.RUNNING);
        assertThat(after.failures()).isEqualTo(1);
        assertThat(taskEventTypes(taskEvents, task.id())).contains("verification_failed");
    }

    @Test
    void uncommittedFilesAreAGap() throws Exception {
        Task task = task();
        Files.writeString(worktrees.resolve(task.id()).resolve("scratch.txt"), "left behind\n");

        RunResult result = verify(runner(), task);

        RunOutcome.Verification v = (RunOutcome.Verification) result.outcome();
        assertThat(v.passed()).isFalse();
        assertThat(v.gaps()).singleElement().satisfies(g -> {
            assertThat(g.source()).isEqualTo("custom");
            assertThat(g.command()).isEqualTo("git status --porcelain");
            assertThat(g.outputTail()).contains("scratch.txt");
        });
        assertThat(evidence.findByTaskAndHead(task.id(), task.headSha())).singleElement()
                .satisfies(e -> {
                    assertThat(e.kind()).isEqualTo("custom");
                    assertThat(e.passed()).isFalse();
                });
        assertThat(tasks.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void commandTimeoutIsAGap() {
        Task task = task();
        long started = System.nanoTime();

        RunResult result = verify(runner(new EvidenceProperties.Command("tests", "sleep 30", Duration.ofSeconds(1))),
                task);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
        RunOutcome.Verification v = (RunOutcome.Verification) result.outcome();
        assertThat(v.gaps()).singleElement().satisfies(g -> {
            assertThat(g.exitCode()).isEqualTo(-1);
            assertThat(g.outputTail()).contains("timed out");
        });
        assertThat(tasks.findById(task.id()).orElseThrow().failures()).isEqualTo(1);
    }

    @Test
    void staleHeadIsANoOp() {
        Task task = task();
        Run run = start(runs, props, task.id(), RunKind.VERIFY, "verifier",
                headInput("0000000000000000000000000000000000000000"));

        RunResult result = runner(command("tests", "true")).execute(run);

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(result.outcome()).isEqualTo(new RunOutcome.Verification(true, false, List.of()));
        assertThat(evidence.findByTask(task.id())).isEmpty();
        Task after = tasks.findById(task.id()).orElseThrow();
        assertThat(after.status()).isEqualTo(TaskStatus.VERIFYING);
        assertThat(after.failures()).isZero();
    }

    @Test
    void commandsSeeOnlyTheAllowlistedEnvironment() {
        Task task = task();
        Map<String, String> host = new HashMap<>(System.getenv());
        host.put("CLAUDE_CODE_OAUTH_TOKEN", "oauth-secret");
        host.put("LITELLM_MASTER_KEY", "master-secret");
        host.put("ORANGE_GITHUB_TOKEN", "gh-secret");
        host.put("SOMETHING_ELSE", "not allowlisted");

        RunResult result = verify(runner(host, command("custom", "env")), task);

        String output = evidence.findByTaskAndHead(task.id(), task.headSha()).getFirst().outputTail();
        assertThat(output).doesNotContain("oauth-secret", "master-secret", "gh-secret", "SOMETHING_ELSE");
        Set<String> names = Arrays.stream(output.split("\n")).filter(l -> l.contains("="))
                .map(l -> l.substring(0, l.indexOf('='))).collect(Collectors.toSet());
        Set<String> allowed = new HashSet<>(harnessProps.claudeCode().passEnv());
        allowed.addAll(List.of("PWD", "SHLVL", "_", "OLDPWD"));
        assertThat(names).isNotEmpty().isSubsetOf(allowed);
        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
    }
}
