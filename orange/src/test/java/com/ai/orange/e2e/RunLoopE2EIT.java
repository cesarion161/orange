package com.ai.orange.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.evidence.Evidence;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.harness.container.ContainerTestSupport;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunTestSupport;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.task.EdgeKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEvent;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The whole loop, nothing driven by hand: the reactor, scheduler, executors and fake Claude Code
 * take a task from ready to integrated, and integrating it unblocks its dependent. Each nested
 * class is its own coordinator (its own evidence command), with its own origin and base repo;
 * {@code InContainers} is the same loop with every run of the task in a container.
 */
class RunLoopE2EIT {

    /** In containers every step also clones, and starts a JVM inside a container. */
    static final Duration CAP = Duration.ofSeconds(120);

    /** A bare origin and a base checkout of it with one commit, as a real project would have. */
    static Path[] originAndBase(String name) {
        Path dir = RunTestSupport.tempDir("e2e-" + name + "-");
        Path origin = dir.resolve("origin.git");
        RunTestSupport.git(dir, "init", "-q", "--bare", "-b", "main", origin.toString());
        Path base = dir.resolve("base");
        try {
            Files.createDirectories(base);
            Files.writeString(base.resolve("README.md"), "# e2e\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        RunTestSupport.git(base, "init", "-q", "-b", "main");
        RunTestSupport.git(base, "config", "user.email", "t@e");
        RunTestSupport.git(base, "config", "user.name", "T");
        RunTestSupport.git(base, "add", ".");
        RunTestSupport.git(base, "commit", "-q", "-m", "initial");
        RunTestSupport.git(base, "remote", "add", "origin", origin.toString());
        RunTestSupport.git(base, "push", "-q", "-u", "origin", "main");
        return new Path[]{origin, base};
    }

    static Path canary(String name) {
        try {
            return Files.writeString(RunTestSupport.tempDir("e2e-" + name + "-canary-").resolve("canary.txt"), "host only\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static void register(DynamicPropertyRegistry r, Path base, String evidenceCommand) {
        RunTestSupport.register(r, RunTestSupport.tempDir("e2e-runs-"), base, RunTestSupport.tempDir("e2e-wt-"));
        r.add("orange.runs.loop-enabled", () -> "true");
        r.add("orange.events.processing-enabled", () -> "true");
        // Notifications drive the loop; quick sweeps are the backstop if one is missed.
        r.add("orange.runs.reactor-sweep", () -> "1s");
        r.add("orange.runs.scheduler-poll", () -> "1s");
        r.add("orange.evidence.commands[0].kind", () -> "tests");
        r.add("orange.evidence.commands[0].command", () -> evidenceCommand);
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + CAP.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting until " + what);
            Thread.sleep(200);
        }
    }

    /** What each nested coordinator shares. */
    abstract static class Loop {
        @Autowired TaskService taskService;
        @Autowired TaskRepository tasks;
        @Autowired TaskEventRepository taskEvents;
        @Autowired RunRepository runs;
        @Autowired EvidenceRepository evidence;
        @Autowired SchedulerPause pause;

        abstract Path origin();

        /** A → B: B is pending until A is integrated. */
        List<Task> graph(String titleA) {
            String a = "a-" + UUID.randomUUID();
            String b = "b-" + UUID.randomUUID();
            return taskService.createGraph(List.of(
                    new TaskDef(a, titleA, "Make orange-fake.txt.", "implementer", null, null),
                    new TaskDef(b, "Then B", "Builds on A.", "implementer", null, null)), List.of(new EdgeKey(a, b)));
        }

        Supplier<Task> fresh(Task t) {
            return () -> tasks.findById(t.id()).orElseThrow();
        }

        List<String> eventTypes(Task t) {
            return taskEvents.findByTaskSince(t.id(), 0, 500).stream().map(TaskEvent::eventType).toList();
        }

        /**
         * A is implemented, verified, reviewed and integrated by the loop alone, and that promotes B.
         *
         * @return A, integrated
         */
        Task readyToIntegrated(String title) throws InterruptedException {
            List<Task> graph = graph(title);
            Task a = graph.get(0);
            Task b = graph.get(1);
            assertThat(b.status()).isEqualTo(TaskStatus.PENDING);

            await("A is integrated", () -> fresh(a).get().status() == TaskStatus.INTEGRATED);

            Task done = fresh(a).get();
            assertThat(done.failures()).isZero();
            assertThat(RunTestSupport.git(origin(), "rev-parse", "refs/heads/main")).as("origin main is A's head")
                    .isEqualTo(done.headSha());
            assertThat(RunTestSupport.git(origin(), "log", "--format=%s", "main")).contains("fake: " + title);
            List<Evidence> rows = evidence.findByTaskAndHead(a.id(), done.headSha());
            assertThat(rows).filteredOn(e -> e.kind().equals("tests")).isNotEmpty().allMatch(Evidence::passed);
            assertThat(rows).filteredOn(e -> e.kind().equals("diff_review")).isNotEmpty().allMatch(Evidence::passed);
            // A post-run step's events land before its run's run_finished: the run stays running until they do.
            assertThat(eventTypes(a)).containsSubsequence("run_queued", "run_started", "verification_started",
                    "run_finished", "verified", "integrated");
            assertThat(runs.findByTask(a.id())).extracting(r -> r.kind())
                    .contains(RunKind.IMPLEMENT, RunKind.VERIFY, RunKind.DIFF_REVIEW, RunKind.INTEGRATE);

            await("B leaves pending", () -> fresh(b).get().status() != TaskStatus.PENDING);
            return done;
        }

        /** Stops this coordinator's loop so it does not keep working on B after the test. */
        @AfterEach
        void stop() {
            pause.pause("test finished", null);
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(TestcontainersConfiguration.class)
    class HappyPath extends Loop {

        static final Path[] REPOS = originAndBase("happy");

        @org.springframework.test.context.DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            register(r, REPOS[1], "test -f orange-fake.txt");
        }

        @Override
        Path origin() {
            return REPOS[0];
        }

        @Test
        void readyToIntegratedPromotesDependents() throws Exception {
            readyToIntegrated("Ship A");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(TestcontainersConfiguration.class)
    class FixLoop extends Loop {

        static final Path[] REPOS = originAndBase("fix");

        @org.springframework.test.context.DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            register(r, REPOS[1], "test -f fixed.txt");
        }

        @Override
        Path origin() {
            return REPOS[0];
        }

        @Test
        void failingEvidenceIsFixedThenIntegrated() throws Exception {
            Task a = graph("Ship A with a fix").get(0);

            await("A is integrated", () -> fresh(a).get().status() == TaskStatus.INTEGRATED);

            Task done = fresh(a).get();
            assertThat(done.failures()).as("the failed verification counted once").isEqualTo(1);
            assertThat(eventTypes(a)).containsSubsequence("verification_failed", "run_queued", "verified", "integrated");
            assertThat(runs.findByTask(a.id())).filteredOn(r -> r.kind() == RunKind.CI_FIX).singleElement()
                    .satisfies(fix -> assertThat(fix.input().data()).contains("test -f fixed.txt"));
            assertThat(RunTestSupport.git(origin(), "log", "--format=%s", "main")).contains("fake: Ship A with a fix");
            assertThat(RunTestSupport.git(origin(), "show", "--stat", "--format=", "main")).contains("fixed.txt");
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(TestcontainersConfiguration.class)
    class InContainers extends Loop {

        static final Path[] REPOS = originAndBase("containers");
        /** A file of the host, in a directory no run is given: an evidence command that sees it ran on the host. */
        static final Path CANARY = canary("containers");

        @org.springframework.test.context.DynamicPropertySource
        static void props(DynamicPropertyRegistry r) {
            register(r, REPOS[1], "test -f orange-fake.txt && test ! -e " + CANARY);
            ContainerTestSupport.register(r);
        }

        @Override
        Path origin() {
            return REPOS[0];
        }

        @Test
        void readyToIntegratedInContainers() throws Exception {
            assertThat(CANARY).exists();

            Task a = readyToIntegrated("Ship A in a container");

            // The agent, the evidence command and the reviewer each had a container; none is left.
            assertThat(runs.findByTask(a.id())).allSatisfy(r ->
                    assertThat(ContainerTestSupport.containersOf(r.id())).as(r.kind().dbValue()).isEmpty());
        }
    }
}
