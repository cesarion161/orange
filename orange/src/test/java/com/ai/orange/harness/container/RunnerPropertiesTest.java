package com.ai.orange.harness.container;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.harness.container.RunnerProperties.Isolation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/**
 * Process mode is the default, and the settings that would give a container the host (its
 * network, the Docker socket, root) stop the start instead.
 */
class RunnerPropertiesTest {

    static RunnerProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("orange.runner", RunnerProperties.class);
    }

    /** {@code orange.runner} as the shipped application.yaml binds it with these variables set, and no others. */
    static RunnerProperties shipped(Map<String, Object> variables) throws Exception {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addLast(new MapPropertySource("variables", variables));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yaml"))
                .forEach(sources::addLast);
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bindOrCreate("orange.runner", RunnerProperties.class);
    }

    static void assertDefaults(RunnerProperties p) {
        assertThat(p.isolation()).isEqualTo(Isolation.PROCESS);
        assertThat(p.contained()).isFalse();
        RunnerProperties.Container c = p.container();
        assertThat(c.docker()).containsExactly("docker");
        assertThat(c.image()).isEqualTo("orange-worker:local");
        assertThat(c.claudeCommand()).containsExactly("claude");
        assertThat(c.network()).isEqualTo("orange-runs");
        assertThat(c.user()).isEmpty();
        assertThat(c.home()).isEqualTo("/home/agent");
        assertThat(c.memory()).isEmpty();
        assertThat(c.cpus()).isEmpty();
        assertThat(c.pidsLimit()).isEqualTo(4096);
        assertThat(c.volumes()).isEmpty();
    }

    @Test
    void defaults() throws Exception {
        assertDefaults(bind(Map.of()));
        assertDefaults(shipped(Map.of()));
        assertDefaults(new RunnerProperties(null, null));
        assertThat(RunnerProperties.Container.defaults()).isEqualTo(bind(Map.of()).container());
    }

    @Test
    void theShippedSettingsTakeTheirVariables() throws Exception {
        RunnerProperties p = shipped(Map.of("ORANGE_RUNNER_ISOLATION", "container", "ORANGE_WORKER_IMAGE", "worker:jdk21",
                "ORANGE_RUNNER_NETWORK", "runs-net", "ORANGE_RUNNER_USER", "1000:1000",
                "ORANGE_RUNNER_MEMORY", "6g", "ORANGE_RUNNER_CPUS", "2"));

        assertThat(p.contained()).isTrue();
        assertThat(p.container().image()).isEqualTo("worker:jdk21");
        assertThat(p.container().network()).isEqualTo("runs-net");
        assertThat(p.container().user()).isEqualTo("1000:1000");
        assertThat(p.container().memory()).isEqualTo("6g");
        assertThat(p.container().cpus()).isEqualTo("2");
    }

    @Test
    void anEmptyVariableMeansTheDefault() throws Exception {
        // A variable left empty in .env binds as an empty string.
        RunnerProperties p = shipped(Map.of("ORANGE_WORKER_IMAGE", "", "ORANGE_RUNNER_NETWORK", " ",
                "ORANGE_RUNNER_USER", "", "ORANGE_RUNNER_MEMORY", "", "ORANGE_RUNNER_CPUS", ""));
        assertDefaults(p);
    }

    @Test
    void listsBind() {
        RunnerProperties p = bind(Map.of("orange.runner.isolation", "container",
                "orange.runner.container.docker[0]", "sudo", "orange.runner.container.docker[1]", "docker",
                "orange.runner.container.claude-command[0]", "java",
                "orange.runner.container.claude-command[1]", "/opt/fake-claude/FakeClaude.java",
                "orange.runner.container.volumes[0]", "orange-gradle-cache:/home/agent/.gradle",
                "orange.runner.container.volumes[1]", " /srv/m2:/home/agent/.m2:ro ",
                "orange.runner.container.pids-limit", "512"));

        assertThat(p.contained()).isTrue();
        assertThat(p.container().docker()).containsExactly("sudo", "docker");
        assertThat(p.container().claudeCommand()).containsExactly("java", "/opt/fake-claude/FakeClaude.java");
        assertThat(p.container().volumes()).containsExactly("orange-gradle-cache:/home/agent/.gradle", "/srv/m2:/home/agent/.m2:ro");
        assertThat(p.container().pidsLimit()).isEqualTo(512);
    }

    @Test
    void theHostsNetworkIsRefused() {
        for (String network : List.of("host", " HOST ", "container:orange-postgres")) {
            assertThatThrownBy(() -> container(network, "", List.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orange.runner.container.network");
        }
        assertThat(container("hosted-runs", "", List.of()).network()).isEqualTo("hosted-runs");
    }

    @Test
    void theDockerSocketIsRefused() {
        for (String volume : List.of("/var/run/docker.sock:/var/run/docker.sock", "/run/user/1000/docker.sock:/docker.sock:ro",
                "cache:/cache/docker.sock")) {
            assertThatThrownBy(() -> container("", "", List.of("orange-cache:/cache", volume)))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orange.runner.container.volumes");
        }
    }

    @Test
    void aRootUserIsRefused() {
        for (String user : List.of("0", "0:0", "0:20", "00:1000", "000")) {
            assertThatThrownBy(() -> container("", user, List.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orange.runner.container.user")
                    .hasMessageContaining("root");
        }
        for (String user : List.of("root", "agent", "1000:", ":1000", "1000:staff", "-1", "1000:20:5", "10 00")) {
            assertThatThrownBy(() -> container("", user, List.of()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orange.runner.container.user");
        }
        assertThat(container("", "1000", List.of()).user()).isEqualTo("1000");
        assertThat(container("", " 501:20 ", List.of()).user()).isEqualTo("501:20");
        // gid 0 is a group, not the root user.
        assertThat(container("", "1000:0", List.of()).user()).isEqualTo("1000:0");
    }

    @Test
    void anImageThatReadsAsAnOptionIsRefused() {
        for (String image : List.of("--privileged", "-v", "worker:local --privileged")) {
            assertThatThrownBy(() -> new RunnerProperties.Container(null, image, null, null, null, null, null, null, 4096, null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("orange.runner.container.image");
        }
        assertThat(new RunnerProperties.Container(null, " registry.example.net:5000/orange/worker:jdk21 ", null, null, null,
                null, null, null, 4096, null).image()).isEqualTo("registry.example.net:5000/orange/worker:jdk21");
    }

    @Test
    void aRefusedSettingStopsTheBinding() {
        assertThatThrownBy(() -> bind(Map.of("orange.runner.container.network", "host")))
                .hasStackTraceContaining("orange.runner.container.network");
        assertThatThrownBy(() -> bind(Map.of("orange.runner.container.user", "0")))
                .hasStackTraceContaining("must not be root");
        assertThatThrownBy(() -> bind(Map.of("orange.runner.container.pids-limit", "0")))
                .hasStackTraceContaining("orange.runner.container.pids-limit");
        assertThatThrownBy(() -> bind(Map.of("orange.runner.isolation", "vm"))).isInstanceOf(RuntimeException.class);
    }

    private static RunnerProperties.Container container(String network, String user, List<String> volumes) {
        return new RunnerProperties.Container(null, null, null, network, user, null, null, null, 4096, volumes);
    }
}
