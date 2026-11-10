package com.ai.orange.harness.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.FakeClaude;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunExit;
import com.ai.orange.harness.RunHandle;
import com.ai.orange.harness.RunSpec;
import com.ai.orange.harness.Sandbox;
import com.ai.orange.harness.claude.ClaudeCodeAdapter;
import com.ai.orange.harness.container.RunnerProperties.Isolation;
import com.ai.orange.json.Json;
import com.ai.orange.routing.Effort;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real adapter with a sandbox: the fake Claude Code runs in a container from the test
 * image, started by the real Docker CLI. No Spring context, no model.
 */
class ContainerAdapterIT {

    static final String TOKEN = "test-plan-token";

    @TempDir Path tmp;
    Path runRoot;
    Path repo;
    UUID runId;
    final List<RunEvent> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        runRoot = tmp.resolve("runs");
        repo = tmp.resolve("repo");
        runId = UUID.randomUUID();
        Files.createDirectories(repo);
        git(repo, "init", "-q", "-b", "main");
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "init");
    }

    @AfterEach
    void noContainerIsLeft() {
        List<String> left = ContainerTestSupport.containersOf(runId);
        for (String id : left) ContainerTestSupport.docker("rm", "-f", id);
        assertThat(left).as("containers of run %s", runId).isEmpty();
    }

    /** The adapter on a container runtime whose host environment holds what must never reach a run. */
    ClaudeCodeAdapter adapter(RunnerProperties.Container settings) {
        // The host command is the fake on the test JVM: a run that went there by mistake would still answer.
        HarnessProperties props = new HarnessProperties(runRoot,
                new HarnessProperties.ClaudeCode(FakeClaude.command(), List.of("PATH", "HOME", "LANG")));
        return new ClaudeCodeAdapter(props, new ContainerRuntime(new RunnerProperties(Isolation.CONTAINER, settings), () -> {
            Map<String, String> host = new HashMap<>(System.getenv());
            host.put("ORANGE_GITHUB_TOKEN", "must-not-leak");
            return host;
        }));
    }

    ClaudeCodeAdapter adapter() {
        return adapter(ContainerTestSupport.settings());
    }

    RunSpec spec(String prompt, boolean readOnly) {
        return new RunSpec(runId, prompt, "You are a test agent.", "claude-sonnet-5-5", "claude-haiku-5-5",
                Effort.MEDIUM, repo, null, List.of("Read"), List.of(), "bypassPermissions", null,
                new Credentials.Subscription(TOKEN), Duration.ofMinutes(1), List.of(),
                new Sandbox(readOnly, DockerCommand.labels(runId, "implement", "test-worker")));
    }

    @Test
    void theRunStreamsItsEventsAndCommitsInTheMountedRepo() throws Exception {
        RunHandle h = adapter().start(spec("Write the output file.", false), events::add);
        RunExit exit = h.exit().get(120, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).as(stderr()).isZero();
        assertThat(exit.killReason()).isNull();
        assertThat(exit.result()).isNotNull();
        assertThat(exit.result().isError()).isFalse();

        List<Class<?>> kinds = events.stream().<Class<?>>map(Object::getClass)
                .filter(c -> c != RunEvent.Stderr.class).toList();
        assertThat(kinds.getFirst()).isEqualTo(RunEvent.Init.class);
        assertThat(kinds).contains(RunEvent.AssistantText.class);
        assertThat(kinds.getLast()).isEqualTo(RunEvent.Result.class);

        // The same path inside and outside: what the fake calls its cwd is the repo on the host.
        JsonNode init = Json.mapper().readTree(Files.readAllLines(runRoot.resolve(runId + "/stream.jsonl")).getFirst());
        assertThat(init.path("cwd").asText()).isEqualTo(repo.toString());
        assertThat(git(repo, "log", "--format=%s %an", "-1")).contains("fake: write FAKE_OUTPUT.md").contains("orange[bot]");
        assertThat(git(repo, "status", "--porcelain")).isBlank();
        assertThat(repo.resolve("FAKE_OUTPUT.md")).exists();
    }

    @Test
    void theRunGetsItsTokenAndNothingOfTheHostsEnvironment() throws Exception {
        adapter().start(spec("Write the output file.", false), events::add).exit().get(120, TimeUnit.SECONDS);

        Path configDir = runRoot.resolve(runId.toString()).resolve("claude-config");
        String raw = Files.readString(configDir.resolve("fake-env.json"));
        assertThat(raw).doesNotContain("ORANGE_GITHUB_TOKEN").doesNotContain("must-not-leak");
        JsonNode env = Json.mapper().readTree(raw);
        assertThat(env.path("CLAUDE_CODE_OAUTH_TOKEN").asText()).isEqualTo(TOKEN);
        assertThat(env.path("HOME").asText()).isEqualTo("/home/agent");
        assertThat(env.path("CLAUDE_CONFIG_DIR").asText()).isEqualTo(configDir.toString());
        assertThat(env.path("GIT_AUTHOR_NAME").asText()).isEqualTo("orange[bot]");
        // Not what finds the daemon, not who orange is, and not orange's PATH: the image has its own.
        assertThat(env.has("DOCKER_HOST")).isFalse();
        assertThat(env.has("USER")).isFalse();
        assertThat(env.has("TMPDIR")).isFalse();
        assertThat(env.path("PATH").asText()).isNotEqualTo(System.getenv("PATH"));

        // The command line is Claude Code's, as on the host, and holds no secret.
        String args = Files.readString(configDir.resolve("fake-args.json"));
        assertThat(args).contains("\"--session-id\",\"" + runId + "\"").contains("\"--permission-mode\",\"bypassPermissions\"");
        assertThat(args).doesNotContain(TOKEN);
    }

    @Test
    void theTokenTheAgentEchoesIsRedacted() throws Exception {
        RunExit exit = adapter().start(spec("[[fake:leak-env]]", false), events::add).exit().get(120, TimeUnit.SECONDS);

        assertThat(exit.exitCode()).as(stderr()).isZero();
        assertThat(events.toString()).doesNotContain(TOKEN).contains("***");
        assertThat(Files.readString(runRoot.resolve(runId + "/stream.jsonl"))).doesNotContain(TOKEN);
        assertThat(Files.readString(runRoot.resolve(runId + "/stderr.log"))).doesNotContain(TOKEN);
    }

    @Test
    void killEndsTheRunAndLeavesNoContainer() throws Exception {
        RunHandle h = adapter().start(spec("[[fake:hang]]", false), events::add);
        Path pidFile = runRoot.resolve(runId + "/claude-config/child.pid");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!Files.exists(pidFile) || Files.readString(pidFile).isBlank()) {
            assertThat(System.nanoTime()).as("fake never started its child: " + stderr()).isLessThan(deadline);
            Thread.sleep(100);
        }
        assertThat(ContainerTestSupport.containersOf(runId)).as("the run's container, found by its label").hasSize(1);

        h.kill("timeout");
        RunExit exit = h.exit().get(30, TimeUnit.SECONDS);

        assertThat(exit.killReason()).isEqualTo("timeout");
        assertThat(h.isAlive()).isFalse();
        // Killing the docker client alone would leave the container, and the agent in it, running.
        assertThat(ContainerTestSupport.containersOf(runId)).isEmpty();
    }

    @Test
    void aKillBeforeTheContainerExistsStillLeavesNone() throws Exception {
        RunHandle h = adapter().start(spec("[[fake:hang]]", false), events::add);
        h.kill("cancelled");
        RunExit exit = h.exit().get(30, TimeUnit.SECONDS);
        assertThat(exit.killReason()).isEqualTo("cancelled");

        // The handle removes the container again when its client has gone, and once more 5 s later.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!ContainerTestSupport.containersOf(runId).isEmpty() && System.nanoTime() < deadline) Thread.sleep(250);
        Thread.sleep(1000);
        assertThat(ContainerTestSupport.containersOf(runId)).isEmpty();
    }

    @Test
    void aReadOnlySandboxCannotWriteItsWorkingDirectory() throws Exception {
        RunExit exit = adapter().start(spec("Write the output file.", true), events::add).exit().get(120, TimeUnit.SECONDS);

        // The fake dies on its first write; what matters is that nothing landed.
        assertThat(exit.exitCode()).isNotZero();
        assertThat(repo.resolve("FAKE_OUTPUT.md")).doesNotExist();
        assertThat(git(repo, "status", "--porcelain")).isBlank();
        assertThat(git(repo, "log", "--format=%s", "-1")).contains("init");
        // Its own config directory stays writable: Claude Code needs it.
        assertThat(runRoot.resolve(runId + "/claude-config/fake-args.json")).exists();
    }

    @Test
    void aMissingImageEndsTheClientWithDockersOwnExitCode() throws Exception {
        RunnerProperties.Container d = ContainerTestSupport.settings();
        RunnerProperties.Container noImage = new RunnerProperties.Container(d.docker(), "orange-no-such-image:local",
                d.claudeCommand(), d.network(), d.user(), d.home(), d.memory(), d.cpus(), d.pidsLimit(), d.volumes());

        RunExit exit = adapter(noImage).start(spec("Write the output file.", false), events::add).exit().get(60, TimeUnit.SECONDS);

        // orange never pulls: the run ends at once, and stderr says what is missing.
        assertThat(exit.exitCode()).isEqualTo(125);
        assertThat(exit.result()).isNull();
        assertThat(DockerCommand.dockerFailed(exit.exitCode())).isTrue();
        assertThat(stderr()).contains("No such image: orange-no-such-image:local");
    }

    @Test
    void aSandboxWithoutARuntimeIsRefused() {
        ClaudeCodeAdapter hostOnly = new ClaudeCodeAdapter(new HarnessProperties(runRoot,
                new HarnessProperties.ClaudeCode(FakeClaude.command(), List.of("PATH", "HOME", "LANG"))));
        // Never a silent fall back to a host process for a run that was to be contained.
        assertThatThrownBy(() -> hostOnly.start(spec("Write the output file.", false), events::add))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("container");
        assertThat(repo.resolve("FAKE_OUTPUT.md")).doesNotExist();
    }

    String stderr() throws Exception {
        Path log = runRoot.resolve(runId + "/stderr.log");
        return Files.exists(log) ? Files.readString(log) : "";
    }

    static String git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor()).as("git " + String.join(" ", args) + ": " + out).isZero();
        return out;
    }
}
