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
 * {@link GithubEventRouter} only logs: orange integrates verified work itself, and a merged PR
 * means something again only in the later PR integration mode (project-space spec §11).
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
    void mergedPrEventChangesNoTask() {
        Task task = verifiedTaskWithPr("repo-1/owner", 42);

        router.route("pull_request", """
                {
                  "action": "closed",
                  "repository": { "full_name": "repo-1/owner" },
                  "pull_request": { "number": 42, "merged": true, "body": "" }
                }
                """);

        assertThat(taskRepo.findById(task.id()).orElseThrow().status()).isEqualTo(TaskStatus.VERIFIED);
    }

    @Test
    void eventsForUnknownPrsAndMalformedBodiesAreIgnored() {
        router.route("pull_request", """
                { "action": "closed", "repository": { "full_name": "repo-1/owner" },
                  "pull_request": { "number": 999, "merged": true, "body": "" } }
                """);
        router.route("pull_request", "not json");
        router.route("pull_request_review", "{}");

        assertThat(dsl.fetchCount(TASKS)).isZero();
    }

    private Task verifiedTaskWithPr(String repo, int prNumber) {
        TaskDef def = new TaskDef("router-it-" + UUID.randomUUID(), "Routing demo", null, "implementer", null, null);
        Task t = taskService.createGraph(List.of(def), List.<EdgeKey>of()).get(0);
        taskRepo.transitionStatus(t.id(), TaskStatus.READY, TaskStatus.RUNNING);
        taskRepo.transitionStatus(t.id(), TaskStatus.RUNNING, TaskStatus.VERIFYING);
        taskRepo.transitionStatus(t.id(), TaskStatus.VERIFYING, TaskStatus.VERIFIED);
        taskRepo.recordPublished(t.id(), repo, "orange/" + t.id(), prNumber,
                "https://github.com/" + repo + "/pull/" + prNumber, null);
        return taskRepo.findById(t.id()).orElseThrow();
    }
}
