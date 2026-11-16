package com.ai.orange.planner;

import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EDGES;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.harness.FakeClaude;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * Drives {@link PlannerService} end-to-end with the fake Claude Code, whose
 * {@code [[fake:plan]]} scenario (selected by a marker in the request) writes a
 * canned 3-task DAG. Verifies the planner is <b>plan-only</b>:
 * it returns the DAG + artifacts for review and creates no tasks (dispatch is a
 * separate step — see {@code DispatchServiceIT}).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlannerServiceIT {

    static Path plannerOutputRoot;
    static Path runRoot;

    @Autowired PlannerService service;
    @Autowired DSLContext dsl;

    @BeforeAll
    static void prepare() throws IOException {
        Path tmp = Files.createTempDirectory("planner-it-");
        plannerOutputRoot = tmp.resolve("plans");
        runRoot = tmp.resolve("runs");
        Files.createDirectories(plannerOutputRoot);
    }

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry r) {
        r.add("orange.planner.output-root", () -> plannerOutputRoot.toString());
        FakeClaude.register(r, runRoot);
    }

    @BeforeEach
    void clean() {
        dsl.deleteFrom(TASK_EVENTS).execute();
        dsl.deleteFrom(TASK_EDGES).execute();
        dsl.deleteFrom(TASKS).execute();
        dsl.deleteFrom(RUNS).where(RUNS.TASK_ID.isNull()).execute();
    }

    @Test
    void plan_returns_dag_for_review_and_creates_no_tasks() throws Exception {
        PlannerService.PlanResult result = service.plan("Build a TODO web app with auth. [[fake:plan]]");

        assertThat(result.status()).isEqualTo("success");

        // The planner ran as a runs row of kind plan, with no task.
        var run = dsl.selectFrom(RUNS).where(RUNS.KIND.eq("plan")).fetchSingle();
        assertThat(run.getStatus()).isEqualTo("succeeded");
        assertThat(run.getTaskId()).isNull();
        assertThat(run.getRole()).isEqualTo("planner");
        assertThat(run.getTier()).isEqualTo("large");

        // The reviewable DAG comes back in the result — 3 tasks, 2 edges.
        assertThat(result.plan()).isNotNull();
        assertThat(result.plan().tasks()).hasSize(3);
        assertThat(result.plan().edges()).hasSize(2);

        var byTitle = result.plan().tasks().stream()
                .collect(java.util.stream.Collectors.toMap(PlanJson.PlanTask::title, t -> t));
        assertThat(byTitle).containsKeys("Build auth module", "Build UI", "Write integration tests");
        assertThat(byTitle.get("Write integration tests").role()).isEqualTo("implementer");

        // Architecture + plan files are reachable on disk.
        assertThat(result.architectureMd()).isNotNull();
        assertThat(Files.exists(result.architectureMd())).isTrue();
        assertThat(result.planJson()).isNotNull();
        assertThat(Files.exists(result.planJson())).isTrue();

        // Plan-only: nothing was inserted — that's the dispatcher's job.
        assertThat(dsl.fetchCount(TASKS)).isZero();
        assertThat(dsl.fetchCount(TASK_EDGES)).isZero();
    }

    @Test
    void planner_output_dir_is_per_plan_uuid_and_persists_after_call() throws Exception {
        PlannerService.PlanResult r1 = service.plan("First request [[fake:plan]]");
        PlannerService.PlanResult r2 = service.plan("Second request [[fake:plan]]");

        assertThat(r1.planId()).isNotEqualTo(r2.planId());
        assertThat(r1.outputDir()).isNotEqualTo(r2.outputDir());
        assertThat(Files.exists(r1.outputDir())).isTrue();
        assertThat(Files.exists(r2.outputDir())).isTrue();
    }

    @Test
    void parsesAskFirstRiskReasonMilestoneAndEvidence() throws Exception {
        Path dir = plannerOutputRoot.resolve(java.util.UUID.randomUUID().toString());
        Files.createDirectories(dir.resolve("repo"));
        String milestone = java.util.UUID.randomUUID().toString();

        PlannerService.PlanResult result = service.plan(dir, "Build checkout. [[fake:plan-ask]] [[fake:plan-risky]] "
                + "[[fake:plan-evidence]] [[fake:plan-milestone:" + milestone + "]]");

        assertThat(result.status()).isEqualTo("success");
        assertThat(result.outputDir()).isEqualTo(dir);
        assertThat(result.planId().toString()).isEqualTo(dir.getFileName().toString());
        assertThat(result.runId()).isNotNull();
        PlanJson plan = result.plan();
        assertThat(plan.askFirst()).isTrue();
        assertThat(plan.reason()).isEqualTo("the request is ambiguous");
        assertThat(plan.tasks().getFirst().risk()).isTrue();
        assertThat(plan.tasks().getFirst().riskReason()).isEqualTo("touches the migrations");
        assertThat(plan.evidence()).containsExactly("sh test.sh");
        assertThat(plan.milestone()).isEqualTo(milestone);
        // It ran in the directory the caller prepared, and saw the clone there at repo/.
        assertThat(dsl.selectFrom(RUNS).where(RUNS.ID.eq(result.runId())).fetchSingle().getKind()).isEqualTo("plan");
        assertThat(Files.readString(runRoot.resolve(result.runId() + "/claude-config/fake-probe.txt")))
                .contains("repo=visible");
        assertThat(Files.exists(dir.resolve("plan.json"))).isTrue();

        PlannerService.PlanResult plain = service.plan("A plain plan [[fake:plan]]");
        assertThat(Files.readString(runRoot.resolve(plain.runId() + "/claude-config/fake-probe.txt")))
                .contains("repo=hidden");
        assertThat(plain.plan().askFirst()).isFalse();
        assertThat(plain.plan().evidence()).isEmpty();
        assertThat(plain.plan().milestone()).isNull();
        assertThat(plain.plan().tasks()).allSatisfy(t -> assertThat(t.risk()).isNotEqualTo(Boolean.TRUE));
    }

    @Test
    void planner_failure_is_reported_without_a_plan() throws Exception {
        PlannerService.PlanResult result = service.plan("This planner run fails. [[fake:error]]");
        assertThat(result.status()).isEqualTo("failure");
        assertThat(result.plan()).isNull();
        assertThat(dsl.selectFrom(RUNS).where(RUNS.KIND.eq("plan")).fetchSingle().getError()).isEqualTo("error_result");
    }

    @Test
    void aPlanWithoutTheRepositoryKeepsWebFetchAndOneWithItDoesNot() throws Exception {
        PlannerService.PlanResult bare = service.plan("Plan from the request alone [[fake:plan]]");
        Path withRepo = plannerOutputRoot.resolve(java.util.UUID.randomUUID().toString());
        Files.createDirectories(withRepo.resolve("repo"));
        PlannerService.PlanResult read = service.plan(withRepo, "Plan from the repo [[fake:plan]]");

        assertThat(Files.readString(runRoot.resolve(bare.runId().toString()).resolve("claude-config")
                .resolve("fake-args.json"))).contains("\"WebFetch\"");
        assertThat(Files.readString(runRoot.resolve(read.runId().toString()).resolve("claude-config")
                .resolve("fake-args.json"))).doesNotContain("WebFetch");
    }
}
