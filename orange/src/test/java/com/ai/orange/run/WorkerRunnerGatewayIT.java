package com.ai.orange.run;

import static com.ai.orange.run.RunTestSupport.runEventsText;
import static com.ai.orange.run.RunTestSupport.runningTask;
import static com.ai.orange.run.RunTestSupport.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
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

/** Gateway tiers: the run gets a LiteLLM virtual key, which is always blocked afterwards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class WorkerRunnerGatewayIT {

    static final Path RUN_ROOT = RunTestSupport.tempDir("worker-run-gw-it-");
    static final Path BASE_REPO = RunTestSupport.baseRepo();
    static final Path WORKTREES = RunTestSupport.tempDir("worker-run-gw-wt-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUN_ROOT, BASE_REPO, WORKTREES);
        r.add("orange.routing.tiers.medium.auth", () -> "gateway");
        r.add("orange.routing.tiers.large.auth", () -> "gateway");
        r.add("orange.gateway.base-url", () -> "http://litellm.test:4000");
    }

    @MockitoBean GatewayClient gateway;
    @Autowired WorkerRunner runner;
    @Autowired RunsProperties props;
    @Autowired RunRepository runs;
    @Autowired RunEventRepository runEvents;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired SchedulerPause pause;

    @BeforeEach
    void stubGateway() {
        pause.resume();
        reset(gateway);
        when(gateway.healthy()).thenReturn(true);
        when(gateway.mint(anyString(), anyList(), any(), any(), anyMap()))
                .thenReturn(new VirtualKey("run-x", "sk-secret-virtual-key", null));
        when(gateway.info("sk-secret-virtual-key")).thenReturn(new KeyInfo(new BigDecimal("0.02"), true));
    }

    RunResult implement(String description) {
        Task task = runningTask(taskService, tasks, description);
        return runner.execute(start(runs, props, task.id(), RunKind.IMPLEMENT, "implementer", "{}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void gatewayTierMintsAndAlwaysBlocks() throws Exception {
        RunResult result = implement("[[fake:error]]");

        assertThat(result.reason()).isEqualTo("error_result");
        ArgumentCaptor<String> alias = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<List<String>> models = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<BigDecimal> budget = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(gateway).mint(alias.capture(), models.capture(), budget.capture(), ttl.capture(), anyMap());
        assertThat(alias.getValue()).isEqualTo("run-" + result.runId());
        assertThat(models.getValue()).containsExactly("claude-sonnet-5-5", "claude-haiku-5-5");
        assertThat(budget.getValue()).isEqualByComparingTo("4");
        assertThat(ttl.getValue()).as("the implement timeout plus the margin")
                .isEqualTo(Duration.ofMinutes(60).plus(Duration.ofMinutes(30)));
        verify(gateway).block("sk-secret-virtual-key");

        String env = Files.readString(RUN_ROOT.resolve(result.runId() + "/claude-config/fake-env.json"));
        assertThat(env).contains("\"ANTHROPIC_AUTH_TOKEN\":\"sk-secret-virtual-key\"")
                .contains("\"ANTHROPIC_BASE_URL\":\"http://litellm.test:4000\"")
                .doesNotContain("CLAUDE_CODE_OAUTH_TOKEN");

        Run row = runs.findById(result.runId()).orElseThrow();
        assertThat(row.gatewayKeyAlias()).isEqualTo("run-" + result.runId());
        assertThat(row.auth()).isEqualTo("gateway");
        assertThat(runEventsText(runEvents, result.runId())).doesNotContain("sk-secret-virtual-key");
    }

    @Test
    void agentEchoedVirtualKeyNeverPersists() {
        RunResult result = implement("[[fake:leak-env]]");

        assertThat(result.status()).as(String.valueOf(result.reason())).isEqualTo(RunStatus.SUCCEEDED);
        assertThat(runEventsText(runEvents, result.runId())).doesNotContain("sk-secret-virtual-key");
        assertThat(runs.findById(result.runId()).orElseThrow().outcome().data()).doesNotContain("sk-secret-virtual-key");
        assertThat(result.lastAssistantText()).doesNotContain("sk-secret-virtual-key");
    }

    @Test
    void gatewayCostComesFromLiteLlm() {
        RunResult result = implement("Print hello.");

        assertThat(result.status()).isEqualTo(RunStatus.SUCCEEDED);
        // LiteLLM's spend wins over the harness's own figure (0.01).
        assertThat(result.costUsd()).isEqualByComparingTo("0.02");
        assertThat(runs.findById(result.runId()).orElseThrow().costUsd()).isEqualByComparingTo("0.02");
    }

    @Test
    void gatewayDownFailsFastAndPausesForFiveMinutes() {
        when(gateway.healthy()).thenReturn(false);

        RunResult result = implement("Print hello.");

        assertThat(result.reason()).isEqualTo("gateway_unavailable");
        verify(gateway, never()).mint(anyString(), anyList(), any(), any(), anyMap());
        assertThat(pause.current()).hasValueSatisfying(p -> {
            assertThat(p.reason()).isEqualTo("gateway_unavailable");
            assertThat(p.until()).isCloseTo(OffsetDateTime.now().plusMinutes(5), within(1, ChronoUnit.MINUTES));
        });
    }

    @Test
    void keyIsBlockedEvenWhenTheHarnessCannotStart() {
        // A cwd that does not exist makes ProcessBuilder.start fail after the key was minted.
        RunResult result = runner.runDirect(RunKind.PLAN, "planner", Path.of("/nonexistent/orange/cwd"),
                "Plan something.", Duration.ofSeconds(60));

        assertThat(result.reason()).isEqualTo("start_failed");
        verify(gateway).block("sk-secret-virtual-key");
        assertThat(runs.findById(result.runId()).orElseThrow().status()).isEqualTo(RunStatus.FAILED);
    }
}
