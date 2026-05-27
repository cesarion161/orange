package com.ai.orange.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.planner.PlanJson;
import com.ai.orange.planner.PlannerService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pins the JSON shape of {@code POST /plans} responses — the DAG contract the
 * orange-mcp {@code _summarise_plan} shim parses ({@code plan.tasks[].key},
 * {@code plan.edges[].from/to}). Pure mapping test, no Spring context.
 */
class PlanResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void of_exposes_reviewable_dag_and_artifact_paths() throws Exception {
        PlanJson plan = new PlanJson(
                List.of(
                        new PlanJson.PlanTask("a", "Title A", "desc A", "dev", 100, "dev_only", null),
                        new PlanJson.PlanTask("b", "Title B", null, "tester", null, null, null)),
                List.of(new PlanJson.PlanEdge("a", "b")));
        UUID planId = UUID.randomUUID();
        PlannerService.PlanResult result = new PlannerService.PlanResult(
                planId, Path.of("/tmp/plans", planId.toString()), "success", plan,
                Path.of("/tmp/plans", planId.toString(), "architecture.md"),
                Path.of("/tmp/plans", planId.toString(), "plan.json"));

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(PlanResponse.of(result)));

        assertThat(json.get("planId").asText()).isEqualTo(planId.toString());
        assertThat(json.get("status").asText()).isEqualTo("success");
        // The DAG the MCP renders for review.
        assertThat(json.get("plan").get("tasks")).hasSize(2);
        assertThat(json.get("plan").get("tasks").get(0).get("key").asText()).isEqualTo("a");
        assertThat(json.get("plan").get("tasks").get(1).get("role").asText()).isEqualTo("tester");
        assertThat(json.get("plan").get("edges")).hasSize(1);
        assertThat(json.get("plan").get("edges").get(0).get("from").asText()).isEqualTo("a");
        assertThat(json.get("plan").get("edges").get(0).get("to").asText()).isEqualTo("b");
        // Artifact PATHS (not bodies) — named to avoid confusion with DispatchRequest.architectureMd.
        assertThat(json.get("planJsonPath").asText()).endsWith("plan.json");
        assertThat(json.get("architectureMdPath").asText()).endsWith("architecture.md");
        // Plan-only: no created-task list on the response.
        assertThat(json.has("tasks")).isFalse();
    }

    @Test
    void of_carries_status_without_a_plan_when_planner_failed() throws Exception {
        PlannerService.PlanResult failed = new PlannerService.PlanResult(
                UUID.randomUUID(), Path.of("/tmp/plans/x"), "timeout", null, null, null);

        JsonNode json = MAPPER.readTree(MAPPER.writeValueAsString(PlanResponse.of(failed)));

        assertThat(json.get("status").asText()).isEqualTo("timeout");
        assertThat(json.get("plan").isNull()).isTrue();
    }
}
