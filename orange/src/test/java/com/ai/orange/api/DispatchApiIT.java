package com.ai.orange.api;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.planner.PlanJson;
import com.ai.orange.planner.PlannerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Web-layer coverage of {@code POST /dispatch}: inline plan, by saved planId,
 * and the validation/not-found error paths. Drives a random-port app via
 * {@link HttpClient}, mirroring {@link TaskApiIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class DispatchApiIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    static Path plannerOutputRoot;

    @Value("${local.server.port}") int port;

    @Autowired PlannerService planner;
    @Autowired DSLContext dsl;

    @BeforeAll
    static void prepare() throws Exception {
        plannerOutputRoot = Files.createTempDirectory("dispatch-api-it-");
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("orange.planner.output-root", () -> plannerOutputRoot.toString());
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(RUNS).execute();
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void dispatch_inline_plan_creates_tasks_and_returns_201() throws Exception {
        Map<String, Object> body = Map.of("plan", twoTaskPlan());

        HttpResponse<String> res = postJson("/dispatch", body);
        assertThat(res.statusCode()).isEqualTo(201);

        JsonNode root = MAPPER.readTree(res.body());
        assertThat(root.get("status").asText()).isEqualTo("success");
        assertThat(root.get("planId").asText()).isNotBlank();
        assertThat(root.get("tasks")).hasSize(2);
        assertThat(dsl.fetchCount(TASKS)).isEqualTo(2);
    }

    @Test
    void dispatch_by_saved_plan_id_creates_tasks_and_returns_201() throws Exception {
        // A planner run leaves plan.json under <output-root>/<planId>/.
        UUID planId = UUID.randomUUID();
        Path dir = Files.createDirectories(plannerOutputRoot.resolve(planId.toString()));
        Files.writeString(dir.resolve("plan.json"), MAPPER.writeValueAsString(twoTaskPlan()));
        assertThat(dsl.fetchCount(TASKS)).isZero();

        HttpResponse<String> res = postJson("/dispatch", Map.of("planId", planId.toString()));
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(MAPPER.readTree(res.body()).get("tasks")).hasSize(2);
        assertThat(dsl.fetchCount(TASKS)).isEqualTo(2);
    }

    @Test
    void dispatch_with_neither_plan_nor_planId_returns_422() throws Exception {
        HttpResponse<String> res = postJson("/dispatch", Map.of("architectureMd", "# only"));
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(MAPPER.readTree(res.body()).get("status").asText()).isEqualTo("invalid_plan");
        assertThat(dsl.fetchCount(TASKS)).isZero();
    }

    @Test
    void dispatch_with_both_plan_and_planId_returns_422() throws Exception {
        Map<String, Object> body = Map.of(
                "plan", twoTaskPlan(),
                "planId", UUID.randomUUID().toString());
        HttpResponse<String> res = postJson("/dispatch", body);
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(dsl.fetchCount(TASKS)).isZero();
    }

    @Test
    void dispatch_inline_cyclic_plan_returns_422() throws Exception {
        // DagCycleException (a RuntimeException, not IllegalArgumentException) must be
        // caught by the controller and mapped to 422 — exercise that handler.
        Map<String, Object> plan = Map.of(
                "tasks", List.of(
                        Map.of("key", "a", "title", "A", "role", "dev"),
                        Map.of("key", "b", "title", "B", "role", "dev")),
                "edges", List.of(
                        Map.of("from", "a", "to", "b"),
                        Map.of("from", "b", "to", "a")));
        HttpResponse<String> res = postJson("/dispatch", Map.of("plan", plan));
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(MAPPER.readTree(res.body()).get("status").asText()).isEqualTo("invalid_plan");
        assertThat(dsl.fetchCount(TASKS)).isZero();
    }

    @Test
    void dispatch_by_unknown_plan_id_returns_404() throws Exception {
        HttpResponse<String> res = postJson("/dispatch", Map.of("planId", UUID.randomUUID().toString()));
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(MAPPER.readTree(res.body()).get("status").asText()).isEqualTo("plan_not_found");
    }

    private static Map<String, Object> twoTaskPlan() {
        return Map.of(
                "tasks", List.of(
                        Map.of("key", "root", "title", "Root task", "role", "dev"),
                        Map.of("key", "leaf", "title", "Leaf task", "role", "dev")),
                "edges", List.of(Map.of("from", "root", "to", "leaf")));
    }

    private HttpResponse<String> postJson(String path, Object body) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build(),
                BodyHandlers.ofString());
    }
}
