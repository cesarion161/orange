package com.ai.orange.postmerge;

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
import com.ai.orange.worktree.WorktreeProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Exercises {@link PostMergeVerifier} with skipped git-sync. We drive the
 * build with a shell command that returns 0 (pass) or 1 (fail) and assert the
 * resulting metadata + events. The passing path uses the @Autowired verifier
 * wired via @TestPropertySource; the failing path constructs a parallel
 * verifier with a different command, reusing the autowired repos.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PostMergeVerifierIT {

    static Path baseRepo;

    @Autowired PostMergeVerifier verifier;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired TaskEventRepository events;
    @Autowired WorktreeProperties worktreeProps;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired DSLContext dsl;

    private PostMergeVerifier configured(String command) {
        // triageOnFail=false so the assertion on event count isn't polluted
        // by the auto-enqueued triage task. No regression command in tests.
        return new PostMergeVerifier(
                tasks, events, taskService, worktreeProps, jdbc,
                command, "", 5, Duration.ofSeconds(10), false);
    }

    @BeforeAll
    static void setup() throws Exception {
        baseRepo = Files.createTempDirectory("post-merge-verifier-it-");
    }

    @DynamicPropertySource
    static void wire(DynamicPropertyRegistry r) {
        r.add("orange.workflow.auto-start", () -> "false");
        r.add("orange.worktrees.base-repo", () -> baseRepo.toString());
        r.add("orange.post-merge.build-command", () -> "true");
        r.add("orange.post-merge.timeout", () -> "10s");
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
    }

    @Test
    void passing_build_stamps_main_verified_at_and_appends_event() {
        UUID taskId = createTask().id();

        PostMergeVerifier.Result result = verifier.verifyAfterMerge(taskId, false);
        assertThat(result).isEqualTo(PostMergeVerifier.Result.PASSED);

        Task reloaded = tasks.findById(taskId).orElseThrow();
        assertThat(reloaded.metadata().data()).contains("main_verified_at");

        int okEvents = dsl.fetchCount(TASK_EVENTS,
                TASK_EVENTS.TASK_ID.eq(taskId).and(TASK_EVENTS.EVENT_TYPE.eq("main_build_ok")));
        assertThat(okEvents).isEqualTo(1);
    }

    @Test
    void failing_build_flags_regression_suspect_and_appends_event() {
        UUID taskId = createTask().id();
        PostMergeVerifier failing = configured("false");

        PostMergeVerifier.Result result = failing.verifyAfterMerge(taskId, false);
        assertThat(result).isEqualTo(PostMergeVerifier.Result.FAILED);

        Task reloaded = tasks.findById(taskId).orElseThrow();
        assertThat(reloaded.metadata().data()).contains("regression_suspect");

        int brokenEvents = dsl.fetchCount(TASK_EVENTS,
                TASK_EVENTS.TASK_ID.eq(taskId).and(TASK_EVENTS.EVENT_TYPE.eq("build_broken")));
        assertThat(brokenEvents).isEqualTo(1);
    }

    @Test
    void blank_build_command_short_circuits_to_skipped() {
        UUID taskId = createTask().id();
        PostMergeVerifier blank = configured("");

        assertThat(blank.verifyAfterMerge(taskId, false))
                .isEqualTo(PostMergeVerifier.Result.SKIPPED);
        Task reloaded = tasks.findById(taskId).orElseThrow();
        assertThat(reloaded.metadata().data()).doesNotContain("main_verified_at");
    }

    @Test
    void failing_build_with_triage_enabled_enqueues_a_triage_task() {
        UUID taskId = createTask().id();
        // triageOnFail=true matches the production wiring.
        PostMergeVerifier failingWithTriage = new PostMergeVerifier(
                tasks, events, taskService, worktreeProps, jdbc,
                "false", "", 5, Duration.ofSeconds(10), true);

        failingWithTriage.verifyAfterMerge(taskId, false);

        int triageTasks = dsl.fetchCount(TASKS, TASKS.ROLE.eq("triage"));
        assertThat(triageTasks).isEqualTo(1);
    }

    @Test
    void regression_fires_every_n_good_builds() {
        // every=2, build passes, regression passes. Run 4 builds; we expect
        // 4 main_build_ok events and 2 regression_pass events.
        PostMergeVerifier verifier = new PostMergeVerifier(
                tasks, events, taskService, worktreeProps, jdbc,
                "true", "true", 2, Duration.ofSeconds(10), false);

        for (int i = 0; i < 4; i++) {
            UUID id = createTask().id();
            verifier.verifyAfterMerge(id, false);
        }

        int regressionPass = dsl.fetchCount(TASK_EVENTS, TASK_EVENTS.EVENT_TYPE.eq("regression_pass"));
        int buildOk = dsl.fetchCount(TASK_EVENTS, TASK_EVENTS.EVENT_TYPE.eq("main_build_ok"));
        assertThat(buildOk).isEqualTo(4);
        assertThat(regressionPass).isEqualTo(2);
    }

    private Task createTask() {
        return taskService.createGraph(
                List.of(new TaskDef("t", "T", null, "dev", null, null, null)),
                List.of()).get(0);
    }
}
