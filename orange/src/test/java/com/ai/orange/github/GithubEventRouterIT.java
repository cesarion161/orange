package com.ai.orange.github;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.task.EdgeKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Verifies {@link GithubEventRouter} translates incoming webhook payloads into
 * the right state transitions, against a real Postgres.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class GithubEventRouterIT {

    @Autowired GithubEventRouter router;
    @Autowired TaskRepository taskRepo;
    @Autowired TaskService taskService;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void merged_pr_event_transitions_pr_open_task_to_dev_ready() {
        Task task = createTaskInPrOpenState("repo-1/owner", 42);

        String payload = """
                {
                  "action": "closed",
                  "repository": { "full_name": "repo-1/owner" },
                  "pull_request": { "number": 42, "merged": true, "body": "" }
                }
                """;

        router.route("pull_request", payload);

        Task reloaded = taskRepo.findById(task.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(TaskStatus.MERGED);
    }

    @Test
    void closed_unmerged_pr_event_does_not_transition_task() {
        Task task = createTaskInPrOpenState("repo-1/owner", 43);

        String payload = """
                {
                  "action": "closed",
                  "repository": { "full_name": "repo-1/owner" },
                  "pull_request": { "number": 43, "merged": false, "body": "" }
                }
                """;

        router.route("pull_request", payload);

        Task reloaded = taskRepo.findById(task.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(TaskStatus.IN_REVIEW);
    }

    @Test
    void merged_pr_for_unknown_task_is_silently_ignored() {
        // No task in DB at all; router should just log and move on.
        String payload = """
                {
                  "action": "closed",
                  "repository": { "full_name": "repo-1/owner" },
                  "pull_request": { "number": 999, "merged": true, "body": "" }
                }
                """;

        router.route("pull_request", payload); // just shouldn't throw

        assertThat(dsl.fetchCount(TASKS)).isZero();
    }

    @Test
    void body_marker_falls_back_when_pr_lookup_misses() {
        // Insert a task without a recorded PR; the router should still find it
        // via the orange-task marker in the PR body.
        Task task = createTaskWithoutPrMetadata();

        String payload = """
                {
                  "action": "closed",
                  "repository": { "full_name": "repo-1/owner" },
                  "pull_request": {
                    "number": 7,
                    "merged": true,
                    "body": "Did the work\\n\\n<!-- orange-task: %s -->"
                  }
                }
                """.formatted(task.id());

        router.route("pull_request", payload);

        Task reloaded = taskRepo.findById(task.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(TaskStatus.MERGED);
    }

    private Task createTaskInPrOpenState(String repo, int prNumber) {
        Task t = createTaskWithoutPrMetadata();
        // Record the PR as if the coordinator had published it.
        taskRepo.recordPublished(t.id(), repo, "orange/" + t.id(), prNumber,
                "https://github.com/" + repo + "/pull/" + prNumber, null);
        return taskRepo.findById(t.id()).orElseThrow();
    }

    private Task createTaskWithoutPrMetadata() {
        TaskDef def = new TaskDef("router-it-" + UUID.randomUUID(),
                "Routing demo", null, "implementer", null, null);
        Task t = taskService.createGraph(List.of(def), List.<EdgeKey>of()).get(0);
        // March it through ready → running → in_review so the merge transition is valid.
        taskRepo.transitionStatus(t.id(), TaskStatus.READY, TaskStatus.RUNNING);
        taskRepo.transitionStatus(t.id(), TaskStatus.RUNNING, TaskStatus.IN_REVIEW);
        return taskRepo.findById(t.id()).orElseThrow();
    }
}
