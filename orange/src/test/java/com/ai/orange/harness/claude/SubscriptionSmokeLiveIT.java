package com.ai.orange.harness.claude;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunExit;
import com.ai.orange.harness.RunSpec;
import com.ai.orange.routing.Effort;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * One real, tool-less Claude Code call on the Max plan: proves the adapter's command line,
 * environment isolation and stream-json parsing against the actual {@code claude} binary.
 * With no tools the model cannot touch the filesystem. Run with {@code ./gradlew liveTest}
 * (CLAUDE_CODE_OAUTH_TOKEN comes from orange/.env).
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "CLAUDE_CODE_OAUTH_TOKEN", matches = ".+")
class SubscriptionSmokeLiveIT {

    @Test
    void toolLessHaikuCallOnTheSubscription() throws Exception {
        Path runRoot = Files.createTempDirectory("orange-live-smoke-runs-");
        Path cwd = Files.createTempDirectory("orange-live-smoke-cwd-");
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(new HarnessProperties(runRoot,
                new HarnessProperties.ClaudeCode(List.of("claude"), List.of("PATH", "HOME", "LANG", "USER", "TMPDIR"))));

        RunSpec spec = new RunSpec(UUID.randomUUID(), "Reply with exactly: ORANGE_OK", null, "claude-haiku-5-5",
                "claude-haiku-5-5", Effort.LOW, cwd, List.of(), List.of(), List.of(), null, null,
                new Credentials.Subscription(System.getenv("CLAUDE_CODE_OAUTH_TOKEN")), Duration.ofMinutes(2));

        List<RunEvent> events = new CopyOnWriteArrayList<>();
        RunExit exit = adapter.start(spec, events::add).exit().get(3, TimeUnit.MINUTES);

        assertThat(exit.exitCode()).as("stderr: %s", Files.exists(runRoot.resolve(spec.runId() + "/stderr.log"))
                ? Files.readString(runRoot.resolve(spec.runId() + "/stderr.log")) : "").isZero();
        assertThat(exit.result()).isNotNull();
        assertThat(exit.result().isError()).isFalse();
        assertThat(exit.result().text()).contains("ORANGE_OK");
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOf(RunEvent.Init.class));
        assertThat(events.stream().filter(RunEvent.ToolUse.class::isInstance)).as("no tools were available").isEmpty();
        assertThat(cwd.toFile().list()).as("a tool-less run writes nothing to its cwd").isEmpty();
    }
}
