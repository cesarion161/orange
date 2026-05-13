package com.ai.orange.api;

import static com.ai.orange.db.jooq.Tables.AGENT_RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.EdgeKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Covers the Phase 8 additions to the task REST surface: list with filter,
 * events with since pagination, cancel. Drives a random-port Spring app
 * via java.net.http.HttpClient (no test-framework HTTP client deps).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class TaskApiIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;

    @Autowired TaskService taskService;
    @Autowired TaskRepository taskRepo;
    @Autowired TaskEventRepository eventRepo;
    @Autowired DSLContext dsl;

    @DynamicPropertySource
    static void disableAutoStart(DynamicPropertyRegistry r) {
        r.add("orange.workflow.auto-start", () -> "false");
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(AGENT_RUNS).execute();
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void list_returns_recent_tasks_newest_first_with_status_filter() throws Exception {
        createTask("alpha");
        Thread.sleep(20); // make created_at order strict
        Task b = createTask("bravo");
        Thread.sleep(20);
        createTask("charlie");
        taskRepo.transitionStatus(b.id(), TaskStatus.READY, TaskStatus.IN_PROGRESS);

        HttpResponse<String> all = get("/tasks?limit=10");
        assertThat(all.statusCode()).isEqualTo(200);
        JsonNode allRoot = MAPPER.readTree(all.body());
        assertThat(allRoot).hasSize(3);
        assertThat(allRoot.get(0).get("title").asText()).isEqualTo("charlie");
        assertThat(allRoot.get(2).get("title").asText()).isEqualTo("alpha");

        HttpResponse<String> ready = get("/tasks?status=ready");
        assertThat(ready.statusCode()).isEqualTo(200);
        JsonNode readyRoot = MAPPER.readTree(ready.body());
        assertThat(readyRoot).hasSize(2);
        assertThat(readyRoot.get(0).get("title").asText()).isEqualTo("charlie");

        HttpResponse<String> bad = get("/tasks?status=wat");
        assertThat(bad.statusCode()).isEqualTo(400);
    }

    @Test
    void events_endpoint_returns_oldest_first_after_since_watermark() throws Exception {
        Task t = createTask("with-events");
        long e1 = eventRepo.append(t.id(), "test", "alpha", JSONB.valueOf("{}"));
        long e2 = eventRepo.append(t.id(), "test", "bravo", JSONB.valueOf("{}"));
        long e3 = eventRepo.append(t.id(), "test", "charlie", JSONB.valueOf("{}"));

        HttpResponse<String> all = get("/tasks/" + t.id() + "/events");
        assertThat(all.statusCode()).isEqualTo(200);
        JsonNode body = MAPPER.readTree(all.body());
        assertThat(body).hasSize(3);
        assertThat(body.get(0).get("id").asLong()).isEqualTo(e1);
        assertThat(body.get(2).get("id").asLong()).isEqualTo(e3);

        HttpResponse<String> after = get("/tasks/" + t.id() + "/events?since=" + e1);
        JsonNode afterBody = MAPPER.readTree(after.body());
        assertThat(afterBody).hasSize(2);
        assertThat(afterBody.get(0).get("id").asLong()).isEqualTo(e2);

        HttpResponse<String> nope = get("/tasks/" + UUID.randomUUID() + "/events");
        assertThat(nope.statusCode()).isEqualTo(404);
    }

    @Test
    void cancel_transitions_non_terminal_task_to_cancelled() throws Exception {
        Task t = createTask("cancel-me");
        HttpResponse<String> res = post("/tasks/" + t.id() + "/cancel");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(MAPPER.readTree(res.body()).get("status").asText()).isEqualTo("CANCELLED");
        assertThat(taskRepo.findById(t.id()).orElseThrow().status()).isEqualTo(TaskStatus.CANCELLED);
    }

    @Test
    void cancel_on_already_terminal_task_returns_409() throws Exception {
        Task t = createTask("already-done");
        taskRepo.transitionStatus(t.id(), TaskStatus.READY, TaskStatus.IN_PROGRESS);
        taskRepo.transitionStatus(t.id(), TaskStatus.IN_PROGRESS, TaskStatus.FAILED);

        HttpResponse<String> res = post("/tasks/" + t.id() + "/cancel");
        assertThat(res.statusCode()).isEqualTo(409);
    }

    @Test
    void cancel_on_unknown_task_returns_404() throws Exception {
        HttpResponse<String> res = post("/tasks/" + UUID.randomUUID() + "/cancel");
        assertThat(res.statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                BodyHandlers.ofString());
    }

    private Task createTask(String title) {
        TaskDef def = new TaskDef("api-it-" + UUID.randomUUID(), title, null,
                "dev", "dev_only", null, null);
        return taskService.createGraph(List.of(def), List.<EdgeKey>of()).get(0);
    }
}
