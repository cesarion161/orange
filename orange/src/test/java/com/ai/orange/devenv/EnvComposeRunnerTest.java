package com.ai.orange.devenv;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.workflow.EnvInfo;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit coverage for {@link EnvComposeRunner}. The actual docker-compose
 * invocation isn't exercised here (would require docker on the test host); we
 * verify the opt-out path and the no-op invariants instead.
 */
class EnvComposeRunnerTest {

    @Test
    void blank_compose_file_disables_the_runner() throws Exception {
        EnvComposeRunner runner = new EnvComposeRunner("",
                Duration.ofMinutes(5), Duration.ofMinutes(1));
        assertThat(runner.enabled()).isFalse();

        // up / down are silent no-ops when disabled; no docker invocation.
        EnvInfo env = new EnvInfo(UUID.randomUUID(), "env-1",
                Map.of("web", 3000, "db", 5432),
                "/data/env-1", "tok-xyz", "default");
        runner.up(env);     // must not throw
        runner.down(env);   // must not throw
    }

    @Test
    void enabled_when_compose_file_configured() {
        EnvComposeRunner runner = new EnvComposeRunner("/tmp/compose.yaml",
                Duration.ofMinutes(5), Duration.ofMinutes(1));
        assertThat(runner.enabled()).isTrue();
    }
}
