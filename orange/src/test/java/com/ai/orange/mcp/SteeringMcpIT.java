package com.ai.orange.mcp;

import static com.ai.orange.db.jooq.Tables.BRIEFS;
import static com.ai.orange.db.jooq.Tables.RUNS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.json.Json;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.run.RunKind;
import com.ai.orange.session.NeutralEvent;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The steering tools over real MCP: the space, the inbox and the sessions, as a Claude Code session uses them. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class SteeringMcpIT {

    @Value("${local.server.port}") int port;
    @Autowired SessionService sessions;
    @Autowired ProjectService projects;
    @Autowired AttentionRepository attention;
    @Autowired DSLContext dsl;

    McpTestClient mcp;
    String name;

    @BeforeEach
    void connect() throws Exception {
        mcp = new McpTestClient("http://localhost:" + port + "/mcp/steering", null);
        mcp.initialize();
        name = "steer-" + UUID.randomUUID().toString().substring(0, 8);
    }

    static ObjectNode args() {
        return Json.mapper().createObjectNode();
    }

    JsonNode call(String tool, ObjectNode args) throws Exception {
        JsonNode result = mcp.callTool(tool, args);
        assertThat(McpTestClient.failed(result)).as(result.toString()).isFalse();
        return Json.mapper().readTree(McpTestClient.text(result));
    }

    String refused(String tool, ObjectNode args) throws Exception {
        JsonNode result = mcp.callTool(tool, args);
        assertThat(McpTestClient.failed(result)).as(result.toString()).isTrue();
        return McpTestClient.text(result);
    }

    Session stoppedSessionIn(String repoRoot) throws Exception {
        String sid = "steer-" + UUID.randomUUID();
        ObjectNode e = args().put("event_id", UUID.randomUUID().toString()).put("harness", "claude-code")
                .put("session_id", sid).put("type", "stopped").put("cwd", repoRoot).put("repo_root", repoRoot)
                .put("branch", "main");
        e.putObject("payload").put("last_message", "Shall I continue?");
        return sessions.apply(NeutralEvent.parse(e));
    }

    @Test
    void theToolsAreListed() throws Exception {
        assertThat(mcp.listTools()).containsExactlyInAnyOrder("orange_projects", "orange_project",
                "orange_project_update", "orange_add", "orange_set", "orange_accept", "orange_drop", "orange_done",
                "orange_attention", "orange_resolve", "orange_sessions", "orange_focus");
    }

    @Test
    void theLatestBriefIsReadWithoutWritingOne() throws Exception {
        int briefsBefore = dsl.fetchCount(BRIEFS);
        int runsBefore = dsl.fetchCount(RUNS, RUNS.KIND.eq(RunKind.BRIEF.dbValue()));
        // Never fresh: true here. This context has no fake claude, so a fresh brief would start the real one.
        JsonNode focus = call("orange_focus", args());
        assertThat(focus.path("state").asText()).isIn("none", "ready");
        if ("none".equals(focus.path("state").asText())) {
            assertThat(focus.has("brief")).isFalse();
            assertThat(focus.path("hint").asText()).contains("fresh");
        } else {
            assertThat(focus.path("brief").path("id").asText()).isNotBlank();
            assertThat(focus.path("brief").path("text").asText()).contains("Waiting on you");
            assertThat(focus.path("brief").path("page").asText()).contains("/brief/").contains("?k=");
        }
        assertThat(call("orange_focus", args().put("fresh", false)).path("state").asText())
                .as("the same answer: nothing was written in between").isEqualTo(focus.path("state").asText());

        assertThat(dsl.fetchCount(BRIEFS)).as("reading writes no brief").isEqualTo(briefsBefore);
        assertThat(dsl.fetchCount(RUNS, RUNS.KIND.eq(RunKind.BRIEF.dbValue()))).as("and starts no brief run")
                .isEqualTo(runsBefore);
    }

    @Test
    void aProjectsSpaceIsBuiltAndRead() throws Exception {
        JsonNode created = call("orange_project_update", args().put("name", name).put("summary", "Steering test"));
        assertThat(created.path("status").asText()).isEqualTo("active");

        JsonNode goal = call("orange_add", args().put("project", name).put("kind", "goal").put("text", "Ship it"));
        call("orange_add", args().put("project", name).put("kind", "milestone").put("text", "Beta").put("due", "2026-12-01"));
        JsonNode better = call("orange_add", args().put("project", name).put("kind", "goal").put("text", "Ship it well")
                .put("supersedes", goal.path("id").asText()));
        ObjectNode set = args().put("project", name).put("key", "evidence");
        set.putArray("value").add("./gradlew test");
        call("orange_set", set);
        call("orange_set", args().put("project", name).put("key", "integration").put("value", "branch"));

        JsonNode space = call("orange_project", args().put("project", name));
        assertThat(space.path("summary").asText()).isEqualTo("Steering test");
        assertThat(space.path("goals")).hasSize(1);
        assertThat(space.path("goals").get(0).path("text").asText()).isEqualTo("Ship it well");
        assertThat(space.path("goals").get(0).path("supersedes").asText()).isEqualTo(goal.path("id").asText());
        assertThat(space.path("goals").get(0).path("author").asText()).isEqualTo("user");
        assertThat(space.path("milestones").get(0).path("due_at").asText()).startsWith("2026-12-01T23:59");
        assertThat(space.path("conventions")).hasSize(2);
        assertThat(space.path("conventions").get(0).path("value").get(0).asText()).isEqualTo("./gradlew test");
        assertThat(space.path("global_conventions").path("quiet-hours").get(0).asText()).isEqualTo("22:00");

        assertThat(call("orange_done", args().put("id", better.path("id").asText())).path("status").asText())
                .isEqualTo("done");
        assertThat(call("orange_project", args().put("project", name)).has("goals")).isFalse();
    }

    @Test
    void badCallsSayWhatToCorrect() throws Exception {
        assertThat(refused("orange_project", args().put("project", "no-such-" + name))).contains("no such project");
        call("orange_project_update", args().put("name", name));
        assertThat(refused("orange_add", args().put("project", name).put("kind", "wish").put("text", "x")))
                .contains("kind");
        assertThat(refused("orange_accept", args().put("id", "abc"))).contains("must be a full id");
        assertThat(refused("orange_add", args().put("project", name).put("kind", "milestone").put("text", "x")
                .put("due", "next week"))).contains("due must be");
        assertThat(refused("orange_project_update", args())).contains("give project");
    }

    @Test
    void aProposedProjectIsConfirmedThroughTheInbox() throws Exception {
        String repoRoot = "/tmp/" + name;
        Session s = stoppedSessionIn(repoRoot);

        JsonNode open = call("orange_attention", args());
        JsonNode proposed = null;
        JsonNode question = null;
        for (JsonNode item : open) {
            if (s.projectId().toString().equals(item.path("project_id").asText())) {
                if ("project_proposed".equals(item.path("kind").asText())) proposed = item;
                if ("session_question".equals(item.path("kind").asText())) question = item;
            }
        }
        assertThat(proposed).isNotNull();
        assertThat(question).isNotNull();
        assertThat(question.path("title").asText()).contains(name).contains("Shall I continue?");

        assertThat(refused("orange_resolve", args().put("id", proposed.path("id").asText()).put("answer", "maybe")))
                .contains("confirm or ignore");
        JsonNode resolved = call("orange_resolve", args().put("id", proposed.path("id").asText()).put("answer", "confirm"));
        assertThat(resolved.path("status").asText()).isEqualTo("resolved");
        assertThat(projects.find(name).orElseThrow().status()).isEqualTo(Project.ACTIVE);

        JsonNode listed = call("orange_projects", args());
        JsonNode row = null;
        for (JsonNode p : listed) if (name.equals(p.path("name").asText())) row = p;
        assertThat(row.path("sessions").asInt()).isEqualTo(1);
        assertThat(row.path("open_attention").asInt()).isEqualTo(1);

        JsonNode rows = call("orange_sessions", args().put("project", name));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).path("status").asText()).isEqualTo("stopped");
        assertThat(rows.get(0).path("waiting_reason").asText()).isEqualTo("question");

        call("orange_resolve", args().put("id", question.path("id").asText()).put("answer", "yes, continue"));
        AttentionItem answered = attention.findById(UUID.fromString(question.path("id").asText())).orElseThrow();
        assertThat(answered.resolution()).isEqualTo("yes, continue");
        assertThat(answered.resolvedBy()).isEqualTo("user");
    }

    @Test
    void attachingARepoMovesItAndItsSessions() throws Exception {
        String repoRoot = "/tmp/" + name + "-repo";
        Session s = stoppedSessionIn(repoRoot);
        call("orange_project_update", args().put("name", name));

        JsonNode updated = call("orange_project_update", args().put("project", name).put("add_repo", repoRoot));
        assertThat(updated.path("repos")).hasSize(1);
        Project target = projects.find(name).orElseThrow();
        assertThat(call("orange_sessions", args().put("project", name)).get(0).path("id").asText())
                .isEqualTo(s.id().toString());
        // The next event from that repo lands in the project that owns it now.
        assertThat(stoppedSessionIn(repoRoot).projectId()).isEqualTo(target.id());
    }

    @Test
    void renamingAProposedProjectToActiveAnswersItsQuestion() throws Exception {
        Session s = stoppedSessionIn("/tmp/" + name + "-x");
        call("orange_project_update", args().put("project", s.projectId().toString()).put("name", name)
                .put("status", "active"));
        assertThat(attention.findByKey("project:" + s.projectId() + ":proposed").orElseThrow().status())
                .isEqualTo(AttentionItem.RESOLVED);
        assertThat(projects.find(name)).isPresent();
    }
}
