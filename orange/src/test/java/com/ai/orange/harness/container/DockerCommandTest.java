package com.ai.orange.harness.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.harness.container.ContainerSpec.Mount;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The docker command line is the boundary of a contained run: every word of it is pinned here. */
class DockerCommandTest {

    static final UUID RUN = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    static final Path DIR = Path.of("/runs/" + RUN);
    static final String SECRET = "not-a-real-token-7c1e";

    static RunnerProperties.Container settings(String memory, String cpus, List<String> volumes) {
        return new RunnerProperties.Container(null, null, null, null, null, null, memory, cpus, 4096, volumes);
    }

    /** An implement run as the adapter will describe it: the clone, the config directory and the MCP config. */
    static ContainerSpec coding(boolean online) {
        // Insertion order is deliberately not the sorted one.
        Map<String, String> env = new LinkedHashMap<>();
        env.put("HOME", "/home/agent");
        env.put("GIT_AUTHOR_NAME", "orange[bot]");
        env.put("CLAUDE_CONFIG_DIR", DIR + "/claude-config");
        env.put("DISABLE_AUTOUPDATER", "1");
        return new ContainerSpec(DockerCommand.runName(RUN), DockerCommand.labels(RUN, "implement", "host-1a2b3c4d"),
                DIR.resolve("workspace"),
                List.of(new Mount(DIR.resolve("workspace"), false), new Mount(DIR.resolve("claude-config"), false),
                        new Mount(DIR.resolve("mcp.json"), true)),
                env, Map.of("CLAUDE_CODE_OAUTH_TOKEN", SECRET), online,
                List.of("claude", "-p", "--output-format", "stream-json", "--mcp-config", DIR + "/mcp.json"));
    }

    @Test
    void theExactCommandLineOfACodingRun() {
        List<String> argv = DockerCommand.run(coding(true), settings("", "", List.of()), "501:20");

        assertThat(argv).containsExactly(
                "docker", "run", "--rm", "-i", "--pull", "never", "--init",
                "--name", "orange-run-" + RUN,
                "--label", "orange.kind=implement", "--label", "orange.run=" + RUN, "--label", "orange.worker=host-1a2b3c4d",
                "--user", "501:20", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--pids-limit", "4096",
                "--network", "orange-runs", "--add-host", "host.docker.internal:host-gateway",
                "--workdir", DIR + "/workspace",
                "--mount", "type=bind,source=" + DIR + "/workspace,target=" + DIR + "/workspace",
                "--mount", "type=bind,source=" + DIR + "/claude-config,target=" + DIR + "/claude-config",
                "--mount", "type=bind,source=" + DIR + "/mcp.json,target=" + DIR + "/mcp.json,readonly",
                "--env", "CLAUDE_CONFIG_DIR=" + DIR + "/claude-config", "--env", "DISABLE_AUTOUPDATER=1",
                "--env", "GIT_AUTHOR_NAME=orange[bot]", "--env", "HOME=/home/agent",
                "--env", "CLAUDE_CODE_OAUTH_TOKEN",
                "orange-worker:local",
                "claude", "-p", "--output-format", "stream-json", "--mcp-config", DIR + "/mcp.json");
    }

    @Test
    void aSecretsValueAppearsNowhere() {
        ContainerSpec c = new ContainerSpec("orange-run-x", Map.of(), DIR, List.of(), Map.of("HOME", "/home/agent"),
                Map.of("CLAUDE_CODE_OAUTH_TOKEN", SECRET, "ANTHROPIC_AUTH_TOKEN", "not-a-real-virtual-key"), true, List.of("claude"));

        List<String> argv = DockerCommand.run(c, settings("", "", List.of()), "501:20");

        assertThat(argv).noneMatch(a -> a.contains(SECRET) || a.contains("not-a-real-virtual-key"));
        // Name only, sorted: docker takes the values from its client's environment.
        assertThat(argv).containsSequence("--env", "HOME=/home/agent", "--env", "ANTHROPIC_AUTH_TOKEN",
                "--env", "CLAUDE_CODE_OAUTH_TOKEN", "orange-worker:local");
    }

    @Test
    void aReadOnlyMountSaysSo() {
        ContainerSpec review = new ContainerSpec("orange-run-x", Map.of(), Path.of("/worktrees/t1"),
                List.of(new Mount(Path.of("/worktrees/t1"), true), new Mount(Path.of("/runs/x/claude-config"), false)),
                Map.of(), Map.of(), true, List.of("claude"));

        List<String> argv = DockerCommand.run(review, settings("", "", List.of()), "501:20");

        assertThat(argv).containsSequence("--mount", "type=bind,source=/worktrees/t1,target=/worktrees/t1,readonly",
                "--mount", "type=bind,source=/runs/x/claude-config,target=/runs/x/claude-config");
    }

    @Test
    void offlineMeansNoNetworkNoHostNameAndNoVolumes() {
        RunnerProperties.Container withVolume = settings("", "", List.of("orange-gradle-cache:/home/agent/.gradle"));

        List<String> offline = DockerCommand.run(coding(false), withVolume, "501:20");
        assertThat(offline).containsSequence("--network", "none", "--workdir");
        assertThat(offline).doesNotContain("--add-host", "--volume", "orange-runs", "orange-gradle-cache:/home/agent/.gradle");

        List<String> online = DockerCommand.run(coding(true), withVolume, "501:20");
        assertThat(online).containsSequence("--network", "orange-runs", "--add-host", "host.docker.internal:host-gateway");
        // After the spec's own mounts, before the environment.
        assertThat(online).containsSequence("--mount", "type=bind,source=" + DIR + "/mcp.json,target=" + DIR + "/mcp.json,readonly",
                "--volume", "orange-gradle-cache:/home/agent/.gradle", "--env");
    }

    @Test
    void limitsOnlyWhenSet() {
        assertThat(DockerCommand.run(coding(true), settings("", "", List.of()), "501:20"))
                .doesNotContain("--memory", "--cpus");

        List<String> limited = DockerCommand.run(coding(true), settings("6g", "2", List.of()), "501:20");
        assertThat(limited).containsSequence("--pids-limit", "4096", "--memory", "6g", "--cpus", "2", "--network");

        RunnerProperties.Container fewer = new RunnerProperties.Container(null, null, null, null, null, null, null, null, 256, null);
        assertThat(DockerCommand.run(coding(true), fewer, "501:20")).containsSequence("--pids-limit", "256");
    }

    @Test
    void neverPrivilegedAndNeverTheSocket() {
        for (boolean online : List.of(true, false)) {
            List<String> argv = DockerCommand.run(coding(online), settings("6g", "2", List.of("cache:/cache:ro")), "501:20");
            assertThat(argv).doesNotContain("--privileged", "--cap-add", "--pid", "--ipc", "--device", "-v");
            assertThat(argv).noneMatch(a -> a.contains("docker.sock") || a.equals("host") || a.startsWith("--network=host"));
            assertThat(argv).containsSequence("--cap-drop", "ALL", "--security-opt", "no-new-privileges");
            assertThat(argv).containsSequence("--pull", "never");
        }
    }

    @Test
    void theSettingsNameTheCliTheImageAndTheNetwork() {
        RunnerProperties.Container custom = new RunnerProperties.Container(List.of("sudo", "-n", "docker"), "worker:jdk",
                null, "runs-net", "1000:1000", null, null, null, 4096, null);

        List<String> argv = DockerCommand.run(coding(true), custom, "1000:1000");

        assertThat(argv.subList(0, 4)).containsExactly("sudo", "-n", "docker", "run");
        assertThat(argv).containsSequence("--user", "1000:1000").containsSequence("--network", "runs-net")
                .containsSequence("--env", "CLAUDE_CODE_OAUTH_TOKEN", "worker:jdk", "claude", "-p");
    }

    @Test
    void aPathThatWouldSplitTheMountIsRefused() {
        for (String bad : List.of("/runs/a,readonly=false", "/runs/a\"b", "/runs/a\nb")) {
            ContainerSpec c = new ContainerSpec("orange-run-x", Map.of(), DIR, List.of(new Mount(Path.of(bad), true)),
                    Map.of(), Map.of(), false, List.of("true"));
            assertThatThrownBy(() -> DockerCommand.run(c, settings("", "", List.of()), "501:20"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("mounted path");
        }
        assertThatThrownBy(() -> new Mount(Path.of("relative/dir"), false)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aVariableNameThatWouldCarryAValueIsRefused() {
        // "--env NAME=value" splits at the first "=": such a name would put a secret's value in the argv.
        ContainerSpec secret = new ContainerSpec("orange-run-x", Map.of(), DIR, List.of(), Map.of(),
                Map.of("TOKEN=" + SECRET, "x"), false, List.of("true"));
        assertThatThrownBy(() -> DockerCommand.run(secret, settings("", "", List.of()), "501:20"))
                .isInstanceOf(IllegalArgumentException.class);

        ContainerSpec plain = new ContainerSpec("orange-run-x", Map.of(), DIR, List.of(), Map.of("A B", "x"),
                Map.of(), false, List.of("true"));
        assertThatThrownBy(() -> DockerCommand.run(plain, settings("", "", List.of()), "501:20"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void namesAndLabelsComeFromTheRunId() {
        assertThat(DockerCommand.runName(RUN)).isEqualTo("orange-run-01234567-89ab-cdef-0123-456789abcdef");
        assertThat(DockerCommand.labels(RUN, "verify", "host-1")).containsExactly(
                Map.entry("orange.kind", "verify"), Map.entry("orange.run", RUN.toString()), Map.entry("orange.worker", "host-1"));
    }

    @Test
    void removeAndListByRun() {
        assertThat(DockerCommand.remove(List.of("docker"), List.of("orange-run-a", "0123abcd")))
                .containsExactly("docker", "rm", "-f", "orange-run-a", "0123abcd");
        assertThat(DockerCommand.listByRun(List.of("docker"), RUN))
                .containsExactly("docker", "ps", "-aq", "--filter", "label=orange.run=" + RUN);
    }

    @Test
    void dockerFailedIsDockersOwnExitCodes() {
        assertThat(List.of(125, 126, 127)).allMatch(DockerCommand::dockerFailed);
        assertThat(List.of(0, 1, 2, 124, 128, 137, 143, -1)).noneMatch(DockerCommand::dockerFailed);
    }
}
