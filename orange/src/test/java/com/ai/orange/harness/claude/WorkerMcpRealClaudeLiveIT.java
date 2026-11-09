package com.ai.orange.harness.claude;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.HarnessProperties;
import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunExit;
import com.ai.orange.harness.RunSpec;
import com.ai.orange.json.Json;
import com.ai.orange.mcp.RunTokens;
import com.ai.orange.mcp.WorkerMcpConfigWriter;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.routing.Effort;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.jooq.JSONB;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The installed {@code claude} against orange's real worker MCP endpoint. It is started with an
 * invalid subscription token, so Anthropic rejects it before any model call; the MCP servers are
 * connected before that, so the init event shows whether Claude Code reached the endpoint and
 * which orange tools it sees. No secret, no cost. Run with {@code ./gradlew liveTest}.
 */
@Tag("live")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WorkerMcpRealClaudeLiveIT {

    @Autowired RunRepository runs;
    @Autowired TaskService taskService;
    @Autowired WorkerMcpConfigWriter configWriter;

    @Test
    void realClaudeConnectsAndSeesOnlyTheImplementTools() throws Exception {
        assumeTrue(claudeInstalled(), "claude is not on PATH");
        UUID task = taskService.createGraph(
                List.of(new TaskDef("live-" + UUID.randomUUID(), "Live MCP check", null, "implementer", null, null)),
                List.of()).get(0).id();
        Run run = runs.insertRunning(RunKind.IMPLEMENT, "implementer", task, "live", Duration.ofMinutes(5),
                JSONB.valueOf("{}"));
        String token = RunTokens.newToken();
        runs.recordRoute(run.id(), "small", "claude-haiku-5-5", "low", "claude-code", "subscription",
                new BigDecimal("1"), null, RunTokens.hash(token), null);

        Path runRoot = Files.createTempDirectory("orange-live-mcp-runs-");
        Path cwd = Files.createTempDirectory("orange-live-mcp-cwd-");
        Path mcpConfig = configWriter.write(Files.createTempDirectory("orange-live-mcp-config-"), token);
        ClaudeCodeAdapter adapter = new ClaudeCodeAdapter(new HarnessProperties(runRoot,
                new HarnessProperties.ClaudeCode(List.of("claude"), List.of("PATH", "HOME", "LANG", "USER", "TMPDIR"))));

        List<String> tools = List.of("Read", "Write", "Edit", "Bash", "Glob", "Grep", "TodoWrite");
        List<String> allowed = new ArrayList<>(tools);
        allowed.addAll(List.of("mcp__orange__report_result", "mcp__orange__request_human"));
        RunSpec spec = new RunSpec(UUID.randomUUID(), "Reply with exactly: ORANGE_OK", "You are orange's MCP check.",
                "claude-haiku-5-5", "claude-haiku-5-5", Effort.LOW, cwd, tools, allowed, List.of(), "acceptEdits",
                mcpConfig, new Credentials.Subscription("sk-ant-oat01-orange-mcp-check-not-a-real-token"),
                Duration.ofMinutes(2));

        List<RunEvent> events = new CopyOnWriteArrayList<>();
        RunExit exit = adapter.start(spec, events::add).exit().get(3, TimeUnit.MINUTES);

        Path runDir = runRoot.resolve(spec.runId().toString());
        JsonNode init = Json.mapper().readTree(Files.readAllLines(runDir.resolve("stream.jsonl")).getFirst());
        System.out.println("claude_code_version=" + init.path("claude_code_version").asText()
                + " mcp_servers=" + init.path("mcp_servers") + " exit=" + exit.exitCode());
        assertThat(init.path("mcp_servers")).as("mcp_servers: %s", init.path("mcp_servers"))
                .anySatisfy(s -> {
                    assertThat(s.path("name").asText()).isEqualTo("orange");
                    assertThat(s.path("status").asText()).isEqualTo("connected");
                });
        List<String> toolNames = new ArrayList<>();
        init.path("tools").forEach(t -> toolNames.add(t.asText()));
        assertThat(toolNames).contains("mcp__orange__report_result", "mcp__orange__request_human");
        assertThat(toolNames).doesNotContain("mcp__orange__submit_review");
        assertThat(runs.findById(run.id()).orElseThrow().outcome()).as("no model call, so no outcome").isNull();
    }

    private static boolean claudeInstalled() {
        try {
            return new ProcessBuilder("claude", "--version").start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
