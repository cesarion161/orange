package com.ai.orange.api;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunEventRepository;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunOutcome;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.RunsProperties;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** Runs, their events and a task's evidence, as the operator sees them. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class RunApiIT {

    static final ObjectMapper MAPPER = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();

    @Value("${local.server.port}") int port;
    @Autowired TaskService taskService;
    @Autowired RunRepository runs;
    @Autowired RunEventRepository runEvents;
    @Autowired EvidenceRepository evidence;
    @Autowired RunsProperties props;
    @Autowired DSLContext dsl;

    Task task() {
        return taskService.createGraph(List.of(new TaskDef("run-api-" + UUID.randomUUID(), "T", "x",
                "implementer", null, null)), List.of()).get(0);
    }

    Run finished(UUID taskId, RunKind kind, OffsetDateTime queuedAt) {
        Run run = runs.insertRunning(kind, "implementer", taskId, props.workerId(), props.leaseTtl(), JSONB.valueOf("{}"));
        dsl.update(RUNS).set(RUNS.QUEUED_AT, queuedAt).where(RUNS.ID.eq(run.id())).execute();
        runs.recordOutcome(run.id(), new RunOutcome.Result("done", "did " + kind.dbValue(), null).toJsonb());
        runs.finish(run.id(), RunStatus.SUCCEEDED, null, new BigDecimal("0.25"), 10, 5, 0, "s");
        return run;
    }

    JsonNode get(String path, int expectedStatus) throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).as(res.body()).isEqualTo(expectedStatus);
        return res.body().isBlank() ? null : MAPPER.readTree(res.body());
    }

    @Test
    void taskRunsAreListedNewestFirst() throws Exception {
        Task task = task();
        OffsetDateTime t0 = OffsetDateTime.now().minusMinutes(10);
        Run first = finished(task.id(), RunKind.IMPLEMENT, t0);
        Run second = finished(task.id(), RunKind.VERIFY, t0.plusMinutes(1));
        Run third = finished(task.id(), RunKind.DIFF_REVIEW, t0.plusMinutes(2));

        JsonNode rows = get("/tasks/" + task.id() + "/runs", 200);

        assertThat(rows).hasSize(3);
        assertThat(List.of(rows.get(0).path("id").asText(), rows.get(1).path("id").asText(), rows.get(2).path("id").asText()))
                .containsExactly(third.id().toString(), second.id().toString(), first.id().toString());
        JsonNode row = rows.get(2);
        assertThat(row.path("kind").asText()).isEqualTo("implement");
        assertThat(row.path("status").asText()).isEqualTo("succeeded");
        assertThat(row.path("role").asText()).isEqualTo("implementer");
        assertThat(row.path("attempt").asInt()).isEqualTo(1);
        assertThat(row.path("cost_usd").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(row.has("queued_at")).isTrue();
        assertThat(row.has("started_at")).isTrue();
        assertThat(row.has("finished_at")).isTrue();
        assertThat(row.path("outcome").path("summary").asText()).isEqualTo("did implement");
    }

    @Test
    void runEventsPageBySeq() throws Exception {
        Run run = runs.insertRunning(RunKind.IMPLEMENT, "implementer", task().id(), props.workerId(), props.leaseTtl(),
                JSONB.valueOf("{}"));
        for (int seq = 1; seq <= 5; seq++) {
            runEvents.append(run.id(), seq, "assistant", JSONB.valueOf("{\"text\":\"message " + seq + "\"}"));
        }

        JsonNode page = get("/runs/" + run.id() + "/events?since=2&limit=2", 200);

        assertThat(page).hasSize(2);
        assertThat(page.get(0).path("seq").asInt()).isEqualTo(3);
        assertThat(page.get(1).path("seq").asInt()).isEqualTo(4);
        assertThat(page.get(0).path("type").asText()).isEqualTo("assistant");
        assertThat(page.get(0).path("payload").path("text").asText()).isEqualTo("message 3");
        assertThat(page.get(0).has("created_at")).isTrue();
    }

    @Test
    void tokenHashIsNotExposed() throws Exception {
        Run run = runs.insertRunning(RunKind.IMPLEMENT, "implementer", task().id(), props.workerId(), props.leaseTtl(),
                JSONB.valueOf("{}"));
        runs.recordRoute(run.id(), "medium", "claude-sonnet-5-5", "high", "claude-code", "subscription",
                new BigDecimal("4"), null, "deadbeefcafe", "/tmp/run");

        JsonNode row = get("/runs/" + run.id(), 200);

        assertThat(row.toString()).doesNotContain("deadbeefcafe").doesNotContain("token");
        assertThat(row.path("model").asText()).isEqualTo("claude-sonnet-5-5");
        assertThat(row.path("tier").asText()).isEqualTo("medium");
        assertThat(row.path("effort").asText()).isEqualTo("high");
        assertThat(row.path("auth").asText()).isEqualTo("subscription");
    }

    @Test
    void unknownRunIs404() throws Exception {
        get("/runs/" + UUID.randomUUID(), 404);
        get("/runs/" + UUID.randomUUID() + "/events", 404);
    }

    @Test
    void evidenceIsListedNewestFirst() throws Exception {
        Task task = task();
        evidence.record(task.id(), null, "aaa", "tests", "./gradlew test", 1, "FooTest FAILED", false);
        Thread.sleep(5);
        evidence.record(task.id(), null, "bbb", "diff_review", null, null, "fine", true);

        JsonNode rows = get("/tasks/" + task.id() + "/evidence", 200);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).path("head_sha").asText()).isEqualTo("bbb");
        assertThat(rows.get(0).path("kind").asText()).isEqualTo("diff_review");
        assertThat(rows.get(0).path("passed").asBoolean()).isTrue();
        assertThat(rows.get(1).path("command").asText()).isEqualTo("./gradlew test");
        assertThat(rows.get(1).path("exit_code").asInt()).isEqualTo(1);
        assertThat(rows.get(1).path("output_tail").asText()).isEqualTo("FooTest FAILED");
        assertThat(rows.get(1).has("created_at")).isTrue();
    }
}
