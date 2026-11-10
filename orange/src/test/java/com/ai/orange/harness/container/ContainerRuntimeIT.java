package com.ai.orange.harness.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.harness.container.ContainerSpec.Mount;
import com.ai.orange.harness.container.RunnerProperties.Isolation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Drives the real Docker CLI with the test image; no Spring context, no model. */
class ContainerRuntimeIT {

    static final String SECRET = "not-a-real-token-5f3a9c";

    @TempDir Path tmp;
    Path dir;
    UUID runId;
    ContainerRuntime runtime;

    @BeforeEach
    void setUp() throws Exception {
        dir = tmp;
        runId = UUID.randomUUID();
        runtime = new ContainerRuntime(new RunnerProperties(Isolation.CONTAINER, ContainerTestSupport.settings()), () -> {
            Map<String, String> host = new HashMap<>(System.getenv());
            host.put("ORANGE_GITHUB_TOKEN", "must-not-leak");
            host.put("LITELLM_MASTER_KEY", "must-not-leak-either");
            return host;
        });
    }

    @AfterEach
    void noContainerIsLeft() {
        List<String> left = ContainerTestSupport.containersOf(runId);
        for (String id : left) ContainerTestSupport.docker("rm", "-f", id);
        assertThat(left).as("containers of run %s", runId).isEmpty();
    }

    ContainerSpec spec(boolean online, Map<String, String> secretEnv, String... command) {
        return new ContainerSpec(DockerCommand.runName(runId), DockerCommand.labels(runId, "verify", "test-worker"), dir,
                List.of(new Mount(dir, false)), Map.of("HOME", runtime.settings().home()), secretEnv, online,
                List.of(command));
    }

    @Test
    void runsAsTheCallerWithOnlyWhatTheSpecGives() throws Exception {
        long uid = new com.sun.security.auth.module.UnixSystem().getUid();
        assertThat(runtime.enabled()).isTrue();
        assertThat(runtime.user()).startsWith(uid + ":");

        ContainerRuntime.Result r = runtime.run(
                spec(true, Map.of("ORANGE_TEST_SECRET", SECRET), "sh", "-c", "id -u; echo \"$HOME\"; pwd; env; echo made > made.txt"),
                Duration.ofSeconds(60));

        assertThat(r.exitCode()).as(r.output()).isZero();
        List<String> lines = r.output().lines().toList();
        assertThat(lines.get(0)).isEqualTo(Long.toString(uid)).isNotEqualTo("0");
        assertThat(lines.get(1)).isEqualTo("/home/agent");
        assertThat(lines.get(2)).isEqualTo(dir.toString());

        Map<String, String> env = new HashMap<>();
        for (String line : lines.subList(3, lines.size())) {
            int eq = line.indexOf('=');
            if (eq > 0) env.put(line.substring(0, eq), line.substring(eq + 1));
        }
        assertThat(env).containsEntry("HOME", "/home/agent").containsEntry("ORANGE_TEST_SECRET", SECRET);
        // Nothing of the host: not what finds the daemon, not who orange is, not its secrets.
        assertThat(env).doesNotContainKeys("DOCKER_HOST", "USER", "TMPDIR", "ORANGE_GITHUB_TOKEN", "LITELLM_MASTER_KEY");
        assertThat(r.output()).doesNotContain("must-not-leak");

        // The mount is the same directory at the same path, and what the run wrote is the caller's to read and delete.
        Path made = dir.resolve("made.txt");
        assertThat(Files.readString(made)).isEqualTo("made\n");
        assertThat(Files.getAttribute(made, "unix:uid")).isEqualTo((int) uid);
        Files.delete(made);
    }

    @Test
    void aSecretsValueIsOnlyInTheClientsEnvironment() throws Exception {
        ProcessBuilder pb = runtime.command(spec(true, Map.of("ORANGE_TEST_SECRET", SECRET), "true"));

        assertThat(pb.command()).noneMatch(a -> a.contains(SECRET));
        assertThat(pb.command()).containsSequence("--env", "ORANGE_TEST_SECRET");
        assertThat(pb.environment()).containsEntry("ORANGE_TEST_SECRET", SECRET);
        // The client gets what it needs to find the daemon and nothing else of orange's environment.
        assertThat(pb.environment()).doesNotContainKeys("ORANGE_GITHUB_TOKEN", "LITELLM_MASTER_KEY", "USER", "TMPDIR");
        assertThat(pb.environment().keySet()).isSubsetOf("PATH", "HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG",
                "DOCKER_CERT_PATH", "DOCKER_TLS_VERIFY", "XDG_RUNTIME_DIR", "ORANGE_TEST_SECRET");
    }

    @Test
    void anOnlineContainerIsOnTheRunNetworkWhichExistsAfterwards() throws Exception {
        // The host has a name (one line per address family), and there is a way out.
        ContainerRuntime.Result r = runtime.run(
                spec(true, Map.of(), "sh", "-c", "grep -c host.docker.internal /etc/hosts; tail -n +2 /proc/net/route | wc -l"),
                Duration.ofSeconds(60));

        assertThat(r.exitCode()).as(r.output()).isZero();
        assertThat(r.output().lines().map(String::strip).map(Integer::parseInt).toList()).hasSize(2).allMatch(n -> n >= 1);
        assertThat(runtime.subnets()).isNotEmpty().allMatch(s -> s.matches("[0-9a-fA-F.:]+/\\d+"));
        String network = runtime.settings().network();
        assertThat(ContainerTestSupport.docker("network", "inspect", "--format",
                "{{.Driver}} {{index .Options \"com.docker.network.bridge.enable_icc\"}}", network).strip())
                .isEqualTo("bridge false");
    }

    @Test
    void anOfflineContainerHasNoNetwork() throws Exception {
        // No interface to a bridge, no route at all, and no name for the host.
        ContainerRuntime.Result r = runtime.run(spec(false, Map.of(), "sh", "-c",
                "test ! -e /sys/class/net/eth0 && tail -n +2 /proc/net/route | wc -l && grep -c host.docker.internal /etc/hosts"),
                Duration.ofSeconds(60));

        // grep -c prints 0 and exits 1 when nothing matched.
        assertThat(r.exitCode()).as(r.output()).isEqualTo(1);
        assertThat(r.output().lines().map(String::strip).toList()).containsExactly("0", "0");
    }

    @Test
    void theCommandsExitCodeAndTheEndOfItsOutputComeBack() throws Exception {
        ContainerRuntime.Result r = runtime.run(
                spec(false, Map.of(), "sh", "-c", "yes 0123456789 | head -c 200000; echo out-end; echo err-end >&2; exit 7"),
                Duration.ofSeconds(60));

        assertThat(r.exitCode()).isEqualTo(7);
        assertThat(r.output().length()).isLessThanOrEqualTo(64 * 1024);
        assertThat(r.output()).contains("out-end").contains("err-end");
    }

    @Test
    void atTheTimeoutTheContainerIsRemoved() throws Exception {
        long started = System.nanoTime();
        ContainerRuntime.Result r = runtime.run(spec(false, Map.of(), "sh", "-c", "echo started; sleep 300"),
                Duration.ofSeconds(4));

        assertThat(r.exitCode()).isEqualTo(-1);
        assertThat(r.output()).contains("started");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(40));
        assertThat(ContainerTestSupport.containersOf(runId)).isEmpty();
    }

    @Test
    void removeByRunRemovesADetachedLabelledContainer() {
        UUID other = UUID.randomUUID();
        String bystander = ContainerTestSupport.docker("run", "-d", "--rm", "--network", "none",
                "--label", DockerCommand.LABEL_RUN + "=" + other, ContainerTestSupport.image(), "sleep", "600").strip();
        try {
            for (String name : List.of(DockerCommand.runName(runId), DockerCommand.runName(runId) + "-c1")) {
                ContainerTestSupport.docker("run", "-d", "--network", "none", "--name", name,
                        "--label", DockerCommand.LABEL_RUN + "=" + runId, ContainerTestSupport.image(), "sleep", "600");
            }
            assertThat(ContainerTestSupport.containersOf(runId)).hasSize(2);

            assertThat(runtime.removeByRun(runId)).isEqualTo(2);

            assertThat(ContainerTestSupport.containersOf(runId)).isEmpty();
            assertThat(runtime.removeByRun(runId)).isZero();
            // Only the run's own: another run's container on the same daemon is not orange's to touch.
            assertThat(ContainerTestSupport.containersOf(other)).hasSize(1);
        } finally {
            ContainerTestSupport.docker("rm", "-f", bystander);
        }
    }

    @Test
    void removeOfAContainerThatIsNotThereDoesNothing() {
        runtime.remove(DockerCommand.runName(runId));
        runtime.remove(DockerCommand.runName(runId));
    }

    @Test
    void aMissingImageIsExitCode125() throws Exception {
        RunnerProperties.Container d = ContainerTestSupport.settings();
        ContainerRuntime noImage = new ContainerRuntime(new RunnerProperties(Isolation.CONTAINER,
                new RunnerProperties.Container(d.docker(), "orange-worker-test-missing:nope", d.claudeCommand(), d.network(),
                        d.user(), d.home(), d.memory(), d.cpus(), d.pidsLimit(), d.volumes())));

        ContainerRuntime.Result r = noImage.run(spec(false, Map.of(), "true"), Duration.ofSeconds(60));

        // --pull never: orange does not go looking for an image it was not given.
        assertThat(r.exitCode()).isEqualTo(125);
        assertThat(DockerCommand.dockerFailed(r.exitCode())).isTrue();
        assertThat(r.output()).contains("No such image");
        noImage.onReady();
    }

    @Test
    void aCommandTheImageLacksIsExitCode127() throws Exception {
        ContainerRuntime.Result r = runtime.run(spec(false, Map.of(), "no-such-command-in-the-image"), Duration.ofSeconds(60));

        assertThat(r.exitCode()).isEqualTo(127);
        assertThat(DockerCommand.dockerFailed(r.exitCode())).isTrue();
    }

    @Test
    void aMissingDockerIsUnavailableAndRemovalsStayQuiet() {
        RunnerProperties.Container d = ContainerTestSupport.settings();
        ContainerRuntime noDocker = new ContainerRuntime(new RunnerProperties(Isolation.CONTAINER,
                new RunnerProperties.Container(List.of(dir.resolve("no-such-docker").toString()), d.image(), d.claudeCommand(),
                        d.network(), d.user(), d.home(), d.memory(), d.cpus(), d.pidsLimit(), d.volumes())));

        assertThatThrownBy(() -> noDocker.run(spec(true, Map.of(), "true"), Duration.ofSeconds(10)))
                .isInstanceOf(ContainerUnavailableException.class).hasMessageContaining("no-such-docker");
        assertThatThrownBy(() -> noDocker.run(spec(false, Map.of(), "true"), Duration.ofSeconds(10)))
                .isInstanceOf(ContainerUnavailableException.class).hasMessageContaining("no-such-docker");
        assertThatThrownBy(() -> noDocker.command(spec(true, Map.of(), "true")))
                .isInstanceOf(ContainerUnavailableException.class);
        noDocker.remove(DockerCommand.runName(runId));
        assertThat(noDocker.removeByRun(runId)).as("nobody can say that the run's containers are gone").isEqualTo(-1);
        // At the start it is a warning, never a failed start, and the network stays unknown.
        noDocker.lookAtNetwork();
        noDocker.lookAgain();
        noDocker.onReady();
        assertThat(noDocker.subnets()).isEmpty();
        assertThat(noDocker.networkSeen()).isFalse();
    }

    @Test
    void theRunNetworkIsLookedAtBeforeTheFirstRequest() {
        ContainerRuntime fresh = new ContainerRuntime(new RunnerProperties(Isolation.CONTAINER, ContainerTestSupport.settings()));
        assertThat(fresh.networkSeen()).isFalse();

        // What Spring calls while the context is built, before the web server opens its port.
        fresh.lookAtNetwork();

        assertThat(fresh.networkSeen()).isTrue();
        assertThat(fresh.subnets()).isNotEmpty();
    }

    @Test
    void theClientsEndIsNotTheContainersSoRunRemovesIt() throws Exception {
        // A client that is killed (the OOM killer, a lost daemon connection) leaves its container running.
        ContainerSpec hanging = spec(false, Map.of(), "sh", "-c", "echo started; sleep 300");
        CompletableFuture<ContainerRuntime.Result> running = CompletableFuture.supplyAsync(() -> {
            try {
                return runtime.run(hanging, Duration.ofMinutes(2));
            } catch (ContainerUnavailableException e) {
                throw new IllegalStateException(e);
            }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (ContainerTestSupport.docker("ps", "-q", "--filter", "label=" + DockerCommand.LABEL_RUN + "=" + runId).isBlank()) {
            assertThat(System.nanoTime()).as("the container never started").isLessThan(deadline);
            Thread.sleep(100);
        }

        ContainerTestSupport.clientOf(hanging.name()).destroyForcibly();

        ContainerRuntime.Result r = running.get(60, TimeUnit.SECONDS);
        assertThat(r.exitCode()).as("the client was killed").isEqualTo(137);
        assertThat(ContainerTestSupport.containersOf(runId)).isEmpty();
    }

    @Test
    void processModeNeverTouchesDocker() {
        ContainerRuntime process = new ContainerRuntime(new RunnerProperties(Isolation.PROCESS, null));

        assertThat(process.enabled()).isFalse();
        process.lookAtNetwork();
        process.lookAgain();
        process.onReady();
        assertThat(process.subnets()).isEmpty();
        assertThat(process.networkSeen()).isFalse();
    }
}
