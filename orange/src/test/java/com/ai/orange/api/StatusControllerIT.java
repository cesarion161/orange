package com.ai.orange.api;

import static com.ai.orange.db.jooq.Tables.AGENT_RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import java.util.List;
import java.util.Map;
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

    final RestTemplate http = new RestTemplate();
    final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS = new ParameterizedTypeReference<>() {};

    @BeforeEach
    void clean() {
        dsl.deleteFrom(AGENT_RUNS).execute();
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void events_live_returns_in_progress_tasks_with_recent_events() {
        taskService.createGraph(
                List.of(new TaskDef("a", "Build A", null, "dev", null, null, null)),
                List.of());
        Task claimed = tasks.claimNextReady("dev", "worker-A").orElseThrow();
        taskEvents.append(claimed.id(), "worker-A", "assistant_message", JSONB.valueOf("{\"text\":\"hi\"}"));

        ResponseEntity<List<Map<String, Object>>> r = http.exchange(
                url("/events/live"), HttpMethod.GET, null, LIST_OF_MAPS);

        assertThat(r.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(r.getBody()).hasSize(1);
        Map<String, Object> row = r.getBody().get(0);
        assertThat(row.get("taskId")).isEqualTo(claimed.id().toString());
        assertThat(row.get("role")).isEqualTo("dev");
        assertThat(row.get("status")).isEqualTo("in_progress");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) row.get("recentEvents");
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).get("eventType")).isEqualTo("assistant_message");
    }

    @Test
    void events_live_role_filter_excludes_other_roles() {
        taskService.createGraph(List.of(
                new TaskDef("a", "Dev task", null, "dev", null, null, null),
                new TaskDef("b", "Chore task", null, "chore", null, null, null)),
                List.of());
        tasks.claimNextReady("dev", "worker-A");
        tasks.claimNextReady("chore", "worker-B");

        ResponseEntity<List<Map<String, Object>>> r = http.exchange(
                url("/events/live?role=chore"), HttpMethod.GET, null, LIST_OF_MAPS);
        assertThat(r.getBody()).hasSize(1);
        assertThat(r.getBody().get(0).get("role")).isEqualTo("chore");
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
