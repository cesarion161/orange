package com.ai.orange.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Subscription-auth runs (the default routing) against the fake Claude Code. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AgentRunServiceIT {

    static final Path RUN_ROOT = AgentRunTestSupport.tempDir("agent-run-it-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        AgentRunTestSupport.fakeHarness(r, RUN_ROOT);
        r.add("orange.billing.subscription-token", () -> "test-plan-token");
    }

    @Autowired AgentRunService service;
    @Autowired TaskService taskService;
    @Autowired AgentRepository agents;
    @Autowired AgentRunRepository agentRuns;
    @Autowired TaskEventRepository taskEvents;

    AgentRunResult run(Task task, String prompt, Duration timeout) throws Exception {
        Agent dev = agents.findEnabledByRole("dev").getFirst();
        Path cwd = Files.createTempDirectory("agent-run-cwd-");
        return service.run(new AgentRunRequest(task.id(), "dev", dev, prompt, cwd, "M", false, timeout, dev.name()));
    }

    @Test
    void successPersistsEventsAndCost() throws Exception {
        Task task = AgentRunTestSupport.newTask(taskService);
        AgentRunResult result = run(task, "Write the output file.", Duration.ofSeconds(60));

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.failureReason()).isNull();
        assertThat(result.lastAssistantText()).isEqualTo("Done.");
        assertThat(result.costUsd()).isEqualByComparingTo("0.01");

        assertThat(AgentRunTestSupport.eventTypes(taskEvents, task.id()))
                .contains("ready", "assistant_message", "final");

        AgentRun row = agentRuns.findById(result.agentRunId()).orElseThrow();
        assertThat(row.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        assertThat(row.costUsd()).isEqualByComparingTo("0.01");
        assertThat(row.tokensIn()).isEqualTo(2700);
        assertThat(row.tokensOut()).isEqualTo(120);
        var meta = AgentRunTestSupport.json(row.metadata());
        // dev on an M task routes as implementer M: medium tier, Sonnet, high effort, subscription
        assertThat(meta.path("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(meta.path("effort").asText()).isEqualTo("high");
        assertThat(meta.path("tier").asText()).isEqualTo("medium");
        assertThat(meta.path("auth").asText()).isEqualTo("subscription");
        assertThat(meta.path("run_id").asText()).isEqualTo(result.runId().toString());
        assertThat(meta.has("gateway_key_alias")).isFalse();

        // The plan token reached the child but nothing orange stored.
        String env = Files.readString(RUN_ROOT.resolve(result.runId() + "/claude-config/fake-env.json"));
        assertThat(env).contains("\"CLAUDE_CODE_OAUTH_TOKEN\":\"test-plan-token\"");
        assertThat(AgentRunTestSupport.eventsText(taskEvents, task.id())).doesNotContain("test-plan-token");
        assertThat(row.metadata().data()).doesNotContain("test-plan-token");
    }

    @Test
    void budgetKill() throws Exception {
        // 5M input tokens on Sonnet at $2/M = $10 > the medium tier's $4 budget.
        AgentRunResult result = run(AgentRunTestSupport.newTask(taskService), "[[fake:expensive]]", Duration.ofSeconds(60));
        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.failureReason()).isEqualTo("budget");
        assertThat(agentRuns.findById(result.agentRunId()).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
    }

    @Test
    void timeoutKill() throws Exception {
        long start = System.nanoTime();
        AgentRunResult result = run(AgentRunTestSupport.newTask(taskService), "[[fake:hang]]", Duration.ofSeconds(3));
        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.failureReason()).isEqualTo("timeout");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(30));
    }

    @Test
    void planLimitDetected() throws Exception {
        AgentRunResult result = run(AgentRunTestSupport.newTask(taskService), "[[fake:plan-limit]]", Duration.ofSeconds(60));
        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.failureReason()).isEqualTo("plan_limit");
    }

    @Test
    void errorResultIsAFailure() throws Exception {
        AgentRunResult result = run(AgentRunTestSupport.newTask(taskService), "[[fake:error]]", Duration.ofSeconds(60));
        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.failureReason()).isEqualTo("error_result");
    }

    @Test
    void earlierFailuresEscalateTheTier() throws Exception {
        Task task = AgentRunTestSupport.newTask(taskService);
        run(task, "[[fake:error]]", Duration.ofSeconds(60));
        run(task, "[[fake:error]]", Duration.ofSeconds(60));
        // Two failed attempts: implementer M (medium/Sonnet) escalates one tier to large/Opus.
        AgentRunResult third = run(task, "Write the output file.", Duration.ofSeconds(60));
        var meta = AgentRunTestSupport.json(agentRuns.findById(third.agentRunId()).orElseThrow().metadata());
        assertThat(meta.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(meta.path("tier").asText()).isEqualTo("large");
    }

    @Test
    void agentEchoedTokenNeverPersists() throws Exception {
        Task task = AgentRunTestSupport.newTask(taskService);
        AgentRunResult result = run(task, "[[fake:leak-env]]", Duration.ofSeconds(60));

        assertThat(result.status()).isEqualTo("success");
        assertThat(AgentRunTestSupport.eventTypes(taskEvents, task.id())).contains("tool_result", "assistant_message");
        assertThat(AgentRunTestSupport.eventsText(taskEvents, task.id())).doesNotContain("test-plan-token");
        assertThat(result.lastAssistantText()).doesNotContain("test-plan-token").contains("***");
        assertThat(result.result().text()).doesNotContain("test-plan-token");
    }

    @Test
    void cancelKillsTheRunningProcess() throws Exception {
        Task task = AgentRunTestSupport.newTask(taskService);
        java.util.concurrent.CompletableFuture<AgentRunResult> running = java.util.concurrent.CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return run(task, "[[fake:hang]]", Duration.ofSeconds(120));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });

        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!service.cancel(task.id())) {
            assertThat(System.nanoTime()).as("run never became cancellable").isLessThan(deadline);
            Thread.sleep(100);
        }

        AgentRunResult result = running.get(30, java.util.concurrent.TimeUnit.SECONDS);
        assertThat(result.status()).isEqualTo("cancelled");
        assertThat(result.failureReason()).isEqualTo("cancelled");
        assertThat(agentRuns.findById(result.agentRunId()).orElseThrow().status()).isEqualTo(AgentRunStatus.CANCELLED);
        assertThat(service.cancel(task.id())).as("nothing left to cancel").isFalse();
    }

    @Test
    void planLimitFailuresDoNotEscalateTheTier() throws Exception {
        Task task = AgentRunTestSupport.newTask(taskService);
        AgentRunResult first = run(task, "[[fake:plan-limit]]", Duration.ofSeconds(60));
        run(task, "[[fake:plan-limit]]", Duration.ofSeconds(60));
        AgentRunResult third = run(task, "Write the output file.", Duration.ofSeconds(60));

        // Running out of plan allowance is not the model failing: no escalation to Opus.
        var meta = AgentRunTestSupport.json(agentRuns.findById(third.agentRunId()).orElseThrow().metadata());
        assertThat(meta.path("tier").asText()).isEqualTo("medium");
        assertThat(meta.path("model").asText()).isEqualTo("claude-sonnet-5-5");

        var failed = AgentRunTestSupport.json(agentRuns.findById(first.agentRunId()).orElseThrow().metadata());
        assertThat(failed.path("failure_reason").asText()).isEqualTo("plan_limit");
        assertThat(failed.path("model").asText()).as("route kept when the reason is added").isEqualTo("claude-sonnet-5-5");
    }

    @Test
    void rejectedCredentialsAreAuthFailuresAndDoNotEscalateTheTier() throws Exception {
        Task task = AgentRunTestSupport.newTask(taskService);
        AgentRunResult first = run(task, "[[fake:auth-failed]]", Duration.ofSeconds(60));
        run(task, "[[fake:auth-failed]]", Duration.ofSeconds(60));
        AgentRunResult third = run(task, "Write the output file.", Duration.ofSeconds(60));

        assertThat(first.status()).isEqualTo("failure");
        assertThat(first.failureReason()).isEqualTo("auth_failed");
        // An expired or revoked token is not the model failing: no escalation to Opus.
        var meta = AgentRunTestSupport.json(agentRuns.findById(third.agentRunId()).orElseThrow().metadata());
        assertThat(meta.path("tier").asText()).isEqualTo("medium");
        assertThat(meta.path("model").asText()).isEqualTo("claude-sonnet-5-5");
    }

    @Test
    void plannerRunsWithoutATaskRow() throws Exception {
        Agent planner = agents.findEnabledByRole("planner").getFirst();
        Path cwd = Files.createTempDirectory("planner-cwd-");
        AgentRunResult result = service.run(new AgentRunRequest(null, "planner", planner, "[[fake:plan]]", cwd,
                null, false, Duration.ofSeconds(60), planner.name()));
        assertThat(result.status()).isEqualTo("success");
        assertThat(result.agentRunId()).isNull();
        assertThat(cwd.resolve("plan.json")).exists();
        String args = Files.readString(RUN_ROOT.resolve(result.runId() + "/claude-config/fake-args.json"));
        assertThat(args).contains("\"--model\",\"claude-opus-5-5\"").contains("\"--effort\",\"xhigh\"");
    }
}
