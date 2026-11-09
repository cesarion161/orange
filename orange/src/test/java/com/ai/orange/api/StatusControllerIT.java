package com.ai.orange.api;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunEventRepository;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class StatusControllerIT {

    @LocalServerPort int port;
    @Autowired DSLContext dsl;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository taskEvents;
    @Autowired RunRepository runs;
    @Autowired RunEventRepository runEvents;
    @Autowired RunsProperties props;
    @Autowired SchedulerPause pause;

    final RestTemplate http = new RestTemplate();
    final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new ParameterizedTypeReference<>() {};

    @BeforeEach
    void clean() {
        dsl.deleteFrom(RUNS).execute();
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    Run runningRun(String title, String role) {
        Task task = taskService.createGraph(List.of(new TaskDef("live-" + UUID.randomUUID(), title, null, role,
                null, null)), List.of()).get(0);
        tasks.transitionStatus(task.id(), TaskStatus.READY, TaskStatus.RUNNING);
        Run run = runs.insertRunning(RunKind.IMPLEMENT, role, task.id(), props.workerId(), props.leaseTtl(),
                JSONB.valueOf("{}"));
        runs.recordRoute(run.id(), "medium", "claude-sonnet-5-5", "high", "claude-code", "subscription",
                new BigDecimal("4"), null, null, "/tmp/run");
        return run;
    }

    @Test
    @SuppressWarnings("unchecked")
    void liveShowsRunningRuns() {
        Run run = runningRun("Build A", "implementer");
        for (int seq = 1; seq <= 5; seq++) {
            runEvents.append(run.id(), seq, "assistant", JSONB.valueOf("{\"text\":\"step " + seq + "\"}"));
        }
        Run finished = runningRun("Done already", "implementer");
        runs.finish(finished.id(), RunStatus.SUCCEEDED, null, BigDecimal.ZERO, 0, 0, 0, null);

        ResponseEntity<List<Map<String, Object>>> r = http.exchange(
                url("/events/live?limit=3"), HttpMethod.GET, null, LIST_OF_MAPS);

        assertThat(r.getBody()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("run_id", run.id().toString())
                    .containsEntry("task_id", run.taskId().toString())
                    .containsEntry("task_title", "Build A")
                    .containsEntry("kind", "implement")
                    .containsEntry("model", "claude-sonnet-5-5")
                    .containsKey("started_at");
            List<Map<String, Object>> recent = (List<Map<String, Object>>) row.get("recent");
            assertThat(recent).extracting(e -> e.get("seq")).as("the last three, newest first").containsExactly(5, 4, 3);
        });
    }

    @Test
    void liveFiltersByRole() {
        runningRun("Dev task", "implementer");
        runningRun("Chore task", "chore");

        ResponseEntity<List<Map<String, Object>>> r = http.exchange(
                url("/events/live?role=chore"), HttpMethod.GET, null, LIST_OF_MAPS);

        assertThat(r.getBody()).singleElement().satisfies(row -> assertThat(row).containsEntry("task_title", "Chore task"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void healthCountsRunsAndPause() {
        Task task = taskService.createGraph(List.of(new TaskDef("h-" + UUID.randomUUID(), "T", null, "implementer",
                null, null)), List.of()).get(0);
        runs.queue(task.id(), RunKind.IMPLEMENT, "implementer", 100, JSONB.valueOf("{}"));
        Run cost = runningRun("Costly", "implementer");
        runs.finish(cost.id(), RunStatus.SUCCEEDED, null, new BigDecimal("1.50"), 0, 0, 0, null);
        pause.pause("maintenance", null);
        try {
            Map<String, Object> body = http.getForObject(url("/health/aggregate"), Map.class);

            Map<String, Object> queues = (Map<String, Object>) body.get("queues");
            assertThat(queues).containsEntry("ready", 1).containsEntry("running", 1)
                    .containsKeys("verifying", "verified", "needs_human");
            Map<String, Object> implement = (Map<String, Object>) ((Map<String, Object>) body.get("runs")).get("implement");
            assertThat(implement).containsEntry("queued", 1).containsEntry("running", 0).containsEntry("slots", 4);
            assertThat((Map<String, Object>) body.get("paused")).containsEntry("reason", "maintenance");
            assertThat(new BigDecimal(String.valueOf(((Map<String, Object>) body.get("cost")).get("total_usd"))))
                    .isEqualByComparingTo("1.50");
            assertThat((Map<String, Object>) body.get("envs")).containsEntry("total", 5);
        } finally {
            pause.resume();
        }
    }

    @Test
    void dev_envs_lists_seeded_envs() {
        ResponseEntity<List<Map<String, Object>>> r = http.exchange(
                url("/dev-envs"), HttpMethod.GET, null, LIST_OF_MAPS);
        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r.getBody()).hasSize(5);  // V3 seeds 5 envs
        assertThat(r.getBody()).allSatisfy(e -> assertThat(e.get("name")).isInstanceOf(String.class));
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }
}
