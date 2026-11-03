package com.ai.orange.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.gateway.GatewayClient;
import com.ai.orange.gateway.KeyInfo;
import com.ai.orange.gateway.VirtualKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** A gateway tier: the run gets a LiteLLM virtual key, which is always blocked afterwards. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AgentRunServiceGatewayIT {

    static final Path RUN_ROOT = AgentRunTestSupport.tempDir("agent-run-gw-it-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        AgentRunTestSupport.fakeHarness(r, RUN_ROOT);
        r.add("orange.billing.subscription-token", () -> "test-plan-token");
        r.add("orange.routing.tiers.medium.auth", () -> "gateway");
        r.add("orange.gateway.base-url", () -> "http://litellm.test:4000");
    }

    @MockitoBean GatewayClient gateway;
    @Autowired AgentRunService service;
    @Autowired TaskService taskService;
    @Autowired AgentRepository agents;
    @Autowired AgentRunRepository agentRuns;
    @Autowired TaskEventRepository taskEvents;

    @BeforeEach
    void stubGateway() {
        reset(gateway);
        when(gateway.healthy()).thenReturn(true);
        when(gateway.mint(anyString(), anyList(), any(), any(), anyMap()))
                .thenReturn(new VirtualKey("run-x", "sk-secret-virtual-key", null));
        when(gateway.info("sk-secret-virtual-key")).thenReturn(new KeyInfo(new BigDecimal("0.02"), true));
    }

    AgentRunResult run(Task task, String prompt) throws Exception {
        Agent dev = agents.findEnabledByRole("dev").getFirst();
        return service.run(new AgentRunRequest(task.id(), "dev", dev, prompt,
                Files.createTempDirectory("gw-cwd-"), "M", false, Duration.ofSeconds(60), dev.name()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void gatewayTierMintsAndAlwaysBlocks() throws Exception {
        Task task = AgentRunTestSupport.newTask(taskService);
        AgentRunResult result = run(task, "[[fake:error]]");

        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.failureReason()).isEqualTo("error_result");

        ArgumentCaptor<String> alias = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> models = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<BigDecimal> budget = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(gateway).mint(alias.capture(), models.capture(), budget.capture(), ttl.capture(), anyMap());
        assertThat(alias.getValue()).isEqualTo("run-" + result.runId());
        assertThat(models.getValue()).containsExactly("claude-sonnet-5-5", "claude-haiku-5-5");
        assertThat(budget.getValue()).isEqualByComparingTo("4");
        assertThat(ttl.getValue()).isEqualTo(Duration.ofSeconds(60).plus(Duration.ofMinutes(30)));
        verify(gateway).block("sk-secret-virtual-key");

        String env = Files.readString(RUN_ROOT.resolve(result.runId() + "/claude-config/fake-env.json"));
        assertThat(env).contains("\"ANTHROPIC_AUTH_TOKEN\":\"sk-secret-virtual-key\"")
                .contains("\"ANTHROPIC_BASE_URL\":\"http://litellm.test:4000\"")
                .doesNotContain("CLAUDE_CODE_OAUTH_TOKEN");

        assertThat(AgentRunTestSupport.eventsText(taskEvents, task.id())).doesNotContain("sk-secret-virtual-key");
        var metadata = agentRuns.findById(result.agentRunId()).orElseThrow().metadata();
        assertThat(metadata.data()).doesNotContain("sk-secret-virtual-key");
        assertThat(AgentRunTestSupport.json(metadata).path("gateway_key_alias").asText()).isEqualTo("run-" + result.runId());
        assertThat(AgentRunTestSupport.json(metadata).path("auth").asText()).isEqualTo("gateway");
    }

    @Test
    void gatewayCostComesFromLiteLlm() throws Exception {
        AgentRunResult result = run(AgentRunTestSupport.newTask(taskService), "Write the output file.");
        assertThat(result.status()).isEqualTo("success");
        // LiteLLM's spend wins over the harness's own estimate (0.01).
        assertThat(result.costUsd()).isEqualByComparingTo("0.02");
        assertThat(agentRuns.findById(result.agentRunId()).orElseThrow().costUsd()).isEqualByComparingTo("0.02");
    }

    @Test
    void gatewayDownFailsFast() throws Exception {
        when(gateway.healthy()).thenReturn(false);
        AgentRunResult result = run(AgentRunTestSupport.newTask(taskService), "Write the output file.");
        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.failureReason()).isEqualTo("gateway_unavailable");
        verify(gateway, never()).mint(anyString(), anyList(), any(), any(), anyMap());
    }

    @Test
    void keyIsBlockedEvenWhenTheHarnessCannotStart() throws Exception {
        // A cwd that does not exist makes ProcessBuilder.start fail after the key was minted.
        Agent dev = agents.findEnabledByRole("dev").getFirst();
        AgentRunResult result = service.run(new AgentRunRequest(AgentRunTestSupport.newTask(taskService).id(), "dev",
                dev, "Write the output file.", Path.of("/nonexistent/orange/cwd"), "M", false, Duration.ofSeconds(60),
                dev.name()));
        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.failureReason()).isEqualTo("start_failed");
        verify(gateway).block("sk-secret-virtual-key");
    }
}
