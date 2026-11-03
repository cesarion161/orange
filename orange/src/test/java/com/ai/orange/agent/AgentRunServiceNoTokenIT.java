package com.ai.orange.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** A subscription tier with no plan token must fail at once, without starting Claude Code. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AgentRunServiceNoTokenIT {

    static final Path RUN_ROOT = AgentRunTestSupport.tempDir("agent-run-notoken-it-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        AgentRunTestSupport.fakeHarness(r, RUN_ROOT);
        r.add("orange.billing.subscription-token", () -> "");
    }

    @Autowired AgentRunService service;
    @Autowired TaskService taskService;
    @Autowired AgentRepository agents;
    @Autowired AgentRunRepository agentRuns;

    @Test
    void missingSubscriptionTokenFailsFast() throws Exception {
        Task task = AgentRunTestSupport.newTask(taskService);
        Agent dev = agents.findEnabledByRole("dev").getFirst();
        AgentRunResult result = service.run(new AgentRunRequest(task.id(), "dev", dev, "Write the output file.",
                Files.createTempDirectory("notoken-cwd-"), "M", false, Duration.ofSeconds(60), dev.name()));

        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.failureReason()).isEqualTo("subscription_token_missing");
        assertThat(agentRuns.findById(result.agentRunId()).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
        try (Stream<Path> runs = Files.list(RUN_ROOT)) {
            assertThat(runs).as("Claude Code must not have been started").isEmpty();
        }
    }
}
