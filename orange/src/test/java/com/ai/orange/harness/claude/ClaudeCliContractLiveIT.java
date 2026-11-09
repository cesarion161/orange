package com.ai.orange.harness.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunExit;
import com.ai.orange.harness.RunSpec;
import com.ai.orange.json.Json;
import com.ai.orange.routing.Effort;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs the installed {@code claude} with orange's full command line and an invalid subscription
 * token. Anthropic rejects the token before any model call, so this needs no secret and costs
 * nothing, yet it proves against the real binary that every flag orange passes is accepted, that
 * the child sees no tools, MCP servers or user plugins, and that a rejected credential is
 * recognised as {@code auth_failed}. Claude Code updates itself on developer machines;
 * {@code FakeClaude} cannot catch that drift. Run with {@code ./gradlew liveTest}.
 */
@Tag("live")
class ClaudeCliContractLiveIT {

    @Test
    void realClaudeAcceptsOrangesCommandLineAndRejectsABadToken() throws Exception {
        assumeTrue(claudeInstalled(), "claude is not on PATH");
        Path runRoot = Files.createTempDirectory("orange-live-cli-runs-");
        Path cwd = Files.createTempDirectory("orange-live-cli-cwd-");
        Path mcpConfig = Files.writeString(runRoot.resolve("mcp.json"), "{\"mcpServers\":{}}");
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(new HarnessProperties(runRoot,
                new HarnessProperties.ClaudeCode(List.of("claude"), List.of("PATH", "HOME", "LANG", "USER", "TMPDIR"))));

        // Every optional flag is set, and no tool is available even if the token were accepted.
        RunSpec spec = new RunSpec(UUID.randomUUID(), "Reply with exactly: ORANGE_OK", "You are orange's CLI contract check.",
                "claude-haiku-5-5", "claude-haiku-5-5", Effort.LOW, cwd, List.of(),
                List.of("Read", "Bash(git status)"), List.of("Bash(rm *)"), "acceptEdits", mcpConfig,
                new Credentials.Subscription("sk-ant-oat01-orange-contract-check-not-a-real-token"), Duration.ofMinutes(2));

        List<RunEvent> events = new CopyOnWriteArrayList<>();
        RunExit exit = adapter.start(spec, events::add).exit().get(3, TimeUnit.MINUTES);

        Path runDir = runRoot.resolve(spec.runId().toString());
        String stderr = Files.exists(runDir.resolve("stderr.log")) ? Files.readString(runDir.resolve("stderr.log")) : "";
        assertThat(stderr).as("claude rejected part of the command line").doesNotContainIgnoringCase("error:");

        JsonNode init = Json.mapper().readTree(Files.readAllLines(runDir.resolve("stream.jsonl")).getFirst());
        System.out.println("claude_code_version=" + init.path("claude_code_version").asText());
        assertThat(init.path("subtype").asText()).isEqualTo("init");
        assertThat(init.path("model").asText()).isEqualTo("claude-haiku-5-5");
        assertThat(init.path("permissionMode").asText()).isEqualTo("acceptEdits");
        assertThat(init.path("tools").isArray() && init.path("tools").isEmpty()).as("tools: %s", init.path("tools")).isTrue();
        assertThat(init.path("mcp_servers").isEmpty()).as("mcp_servers: %s", init.path("mcp_servers")).isTrue();
        assertThat(init.path("plugins")).as("only Claude Code's built-in plugins load, never the user's")
                .allSatisfy(p -> assertThat(p.path("source").asText()).endsWith("@builtin"));

        assertThat(exit.killReason()).isNull();
        assertThat(exit.result()).isNotNull();
        assertThat(exit.result().isError()).isTrue();
        assertThat(AuthFailure.matches(exit.result().text())).as("result: %s", exit.result().text()).isTrue();
        assertThat(events).anySatisfy(e -> assertThat(e).isInstanceOf(RunEvent.Init.class));
        assertThat(events.stream().filter(RunEvent.ToolUse.class::isInstance)).isEmpty();
        assertThat(cwd.toFile().list()).isEmpty();
    }

    private static boolean claudeInstalled() {
        try {
            return new ProcessBuilder("claude", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
