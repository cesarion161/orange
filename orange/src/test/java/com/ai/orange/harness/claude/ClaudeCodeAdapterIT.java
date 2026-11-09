package com.ai.orange.harness.claude;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.FakeClaude;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunExit;
import com.ai.orange.harness.RunHandle;
import com.ai.orange.harness.RunSpec;
import com.ai.orange.routing.Effort;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real adapter against the fake Claude Code; no Spring context. */
class ClaudeCodeAdapterIT {

    @TempDir Path tmp;
    Path runRoot;
    Path repo;
    ClaudeCodeAdapter adapter;
    final List<RunEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        runRoot = tmp.resolve("runs");
        repo = tmp.resolve("repo");
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "init");

        HarnessProperties props = new HarnessProperties(runRoot,
                new HarnessProperties.ClaudeCode(FakeClaude.command(), List.of("PATH", "HOME", "LANG")));
        adapter = new ClaudeCodeAdapter(props, () -> {
            Map<String, String> host = new HashMap<>(System.getenv());
            host.put("ORANGE_GITHUB_TOKEN", "must-not-leak");
            return host;
        });
    }

    RunSpec spec(UUID runId, String prompt) {
        return new RunSpec(runId, prompt, "You are a test agent.", "claude-sonnet-5-5", "claude-haiku-5-5",
                Effort.MEDIUM, repo, null, List.of("Read"), List.of(), "acceptEdits", null,
                new Credentials.Subscription("test-plan-token"), Duration.ofMinutes(1));
    }

    @Test
    void successStreamsEventsAndExitsZero() throws Exception {
        RunHandle h = adapter.start(spec(UUID.randomUUID(), "Write the output file."), events::add);
        RunExit exit = h.exit().get(60, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).isZero();
        assertThat(exit.killReason()).isNull();
        assertThat(exit.result()).isNotNull();
        assertThat(exit.result().isError()).isFalse();
        assertThat(exit.result().costUsd()).isEqualByComparingTo(new BigDecimal("0.01"));

        List<Class<?>> kinds = events.stream().<Class<?>>map(Object::getClass)
                .filter(c -> c != RunEvent.Stderr.class).toList();
        assertThat(kinds.getFirst()).isEqualTo(RunEvent.Init.class);
        assertThat(kinds).contains(RunEvent.AssistantText.class);
        assertThat(kinds.getLast()).isEqualTo(RunEvent.Result.class);
        assertThat(kinds.indexOf(RunEvent.AssistantText.class)).isLessThan(kinds.lastIndexOf(RunEvent.Result.class));

        assertThat(git(repo, "log", "--format=%s %an", "-1")).contains("fake: write FAKE_OUTPUT.md").contains("orange[bot]");
        assertThat(git(repo, "status", "--porcelain")).isBlank();
    }

    @Test
    void promptArrivesOnStdinAndArgsCarryTheSpec() throws Exception {
        UUID runId = UUID.randomUUID();
        adapter.start(spec(runId, "-- a prompt with leading dashes"), events::add).exit().get(60, TimeUnit.SECONDS);
        String args = Files.readString(runRoot.resolve(runId + "/claude-config/fake-args.json"));
        assertThat(args).contains("\"--session-id\",\"" + runId + "\"").contains("\"--effort\",\"medium\"");
        assertThat(args).doesNotContain("leading dashes");
    }

    @Test
    void childEnvIsIsolated() throws Exception {
        UUID runId = UUID.randomUUID();
        adapter.start(spec(runId, "Write the output file."), events::add).exit().get(60, TimeUnit.SECONDS);

        Path configDir = runRoot.resolve(runId.toString()).resolve("claude-config");
        String env = Files.readString(configDir.resolve("fake-env.json"));
        assertThat(env).doesNotContain("ORANGE_GITHUB_TOKEN").doesNotContain("must-not-leak");
        assertThat(env).contains("\"CLAUDE_CONFIG_DIR\":\"" + configDir + "\"");
        assertThat(env).contains("\"CLAUDE_CODE_OAUTH_TOKEN\":\"test-plan-token\"");
        assertThat(configDir.startsWith(runRoot)).isTrue();
        assertThat(repo.resolve("claude-config")).doesNotExist();
    }

    @Test
    void killTerminatesProcessTree() throws Exception {
        UUID runId = UUID.randomUUID();
        RunHandle h = adapter.start(spec(runId, "[[fake:hang]]"), events::add);
        Path pidFile = runRoot.resolve(runId + "/claude-config/child.pid");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) {
            assertThat(System.nanoTime()).as("fake never started its child").isLessThan(deadline);
            Thread.sleep(100);
        }
        long childPid = Long.parseLong(Files.readString(pidFile).trim());
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive)).contains(true);

        h.kill("timeout");
        RunExit exit = h.exit().get(10, TimeUnit.SECONDS);

        assertThat(exit.killReason()).isEqualTo("timeout");
        assertThat(h.isAlive()).isFalse();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < until) {
            Thread.sleep(50);
        }
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    @Test
    void firstKillReasonWins() throws Exception {
        RunHandle h = adapter.start(spec(UUID.randomUUID(), "[[fake:hang]]"), events::add);
        h.kill("budget");
        h.kill("timeout");
        assertThat(h.exit().get(10, TimeUnit.SECONDS).killReason()).isEqualTo("budget");
    }

    @Test
    void errorScenarioReportsErrorResult() throws Exception {
        RunExit exit = adapter.start(spec(UUID.randomUUID(), "[[fake:error]]"), events::add)
                .exit().get(60, TimeUnit.SECONDS);
        assertThat(exit.exitCode()).isEqualTo(1);
        assertThat(exit.killReason()).isNull();
        assertThat(exit.result().isError()).isTrue();
    }

    @Test
    void streamIsTeedToDisk() throws Exception {
        UUID runId = UUID.randomUUID();
        adapter.start(spec(runId, "Write the output file."), events::add).exit().get(60, TimeUnit.SECONDS);
        List<String> lines = Files.readAllLines(runRoot.resolve(runId + "/stream.jsonl"));
        long parsedEvents = events.stream().filter(e -> !(e instanceof RunEvent.Stderr)).count();
        // init, assistant, user, assistant, result = 5 stdout lines from the fake's success scenario
        assertThat(lines).hasSize(5);
        assertThat(lines.getFirst()).contains("\"subtype\":\"init\"");
        assertThat(lines.getLast()).contains("\"type\":\"result\"");
        assertThat(parsedEvents).isGreaterThanOrEqualTo(5);
    }

    @Test
    void credentialsTheAgentEchoesAreRedactedEverywhere() throws Exception {
        UUID runId = UUID.randomUUID();
        RunExit exit = adapter.start(spec(runId, "[[fake:leak-env]]"), events::add).exit().get(60, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).isZero();
        assertThat(events).isNotEmpty();
        assertThat(events.toString()).doesNotContain("test-plan-token").contains("***");
        assertThat(exit.result().text()).doesNotContain("test-plan-token");
        assertThat(Files.readString(runRoot.resolve(runId + "/stream.jsonl"))).doesNotContain("test-plan-token");
        assertThat(Files.readString(runRoot.resolve(runId + "/stderr.log"))).doesNotContain("test-plan-token");
    }

    @Test
    void sessionTranscriptsAreNotPersisted() throws Exception {
        UUID runId = UUID.randomUUID();
        adapter.start(spec(runId, "Write the output file."), events::add).exit().get(60, TimeUnit.SECONDS);
        String args = Files.readString(runRoot.resolve(runId + "/claude-config/fake-args.json"));
        // Claude Code's own transcript would be one more unredacted copy of everything on disk.
        assertThat(args).contains("\"--no-session-persistence\"");
    }

    @Test
    void sinkExceptionsDoNotBreakTheRun() throws Exception {
        RunExit exit = adapter.start(spec(UUID.randomUUID(), "Write the output file."), e -> {
            throw new IllegalStateException("sink blew up");
        }).exit().get(60, TimeUnit.SECONDS);
        assertThat(exit.exitCode()).isZero();
        assertThat(exit.result()).isNotNull();
    }

    static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as("git " + String.join(" ", args) + ": " + out).isZero();
        return out;
    }
}
