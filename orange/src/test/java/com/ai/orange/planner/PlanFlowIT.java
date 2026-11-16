package com.ai.orange.planner;

import static com.ai.orange.db.jooq.Tables.TASKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.db.jooq.tables.records.TasksRecord;
import com.ai.orange.json.Json;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceRepository;
import com.ai.orange.project.SpaceService;
import com.ai.orange.repo.FakeGithub;
import com.ai.orange.repo.RepoKey;
import com.ai.orange.run.RunTestSupport;
import com.ai.orange.run.SchedulerPause;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A plan of a project from the request to tasks (remote-first spec §7.2): planned in the background on
 * a clone of the project's repo, then dispatched on its own or held for the user's approve, drop or
 * note. The fake Claude Code plans; a directory of bare repositories stands in for GitHub.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class PlanFlowIT {

    static final FakeGithub GITHUB = new FakeGithub(RunTestSupport.tempDir("plan-flow-gh-"));
    static final Path RUNS = RunTestSupport.tempDir("plan-flow-runs-");
    static final Path PLANS = RunTestSupport.tempDir("plan-flow-plans-");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        RunTestSupport.register(r, RUNS, RunTestSupport.baseRepo(), RunTestSupport.tempDir("plan-flow-wt-"));
        r.add("orange.planner.output-root", PLANS::toString);
        r.add("orange.repos.root", () -> RunTestSupport.tempDir("plan-flow-mirrors-").toString());
        r.add("orange.repos.github-url", GITHUB::url);
    }

    @Autowired PlanService service;
    @Autowired PlanRepository plans;
    @Autowired ProjectService projects;
    @Autowired SpaceService space;
    @Autowired SpaceRepository spaceItems;
    @Autowired AttentionService attention;
    @Autowired AttentionRepository attentionItems;
    @Autowired SchedulerPause pause;
    @Autowired DSLContext dsl;
    @Autowired TransactionTemplate tx;

    @AfterEach
    void resume() {
        pause.resume();
    }

    Project project(boolean withEvidence) throws Exception {
        String name = "plan-" + UUID.randomUUID().toString().substring(0, 8);
        RepoKey key = GITHUB.create("me/" + name);
        Project p = projects.create(name, null);
        projects.addGithubRepo(p.id(), key);
        if (withEvidence) {
            space.setConvention(p.id(), null, "evidence", Json.mapper().readTree("[\"sh test.sh\"]"),
                    SpaceService.Author.USER);
        }
        return p;
    }

    Plan fresh(Plan p) {
        return plans.findById(p.id()).orElseThrow();
    }

    Plan settled(Plan p) throws InterruptedException {
        await("plan " + p.id() + " leaves planning", () -> !Plan.PLANNING.equals(fresh(p).status()));
        return fresh(p);
    }

    List<TasksRecord> tasksOf(Plan p) {
        return dsl.selectFrom(TASKS).where(TASKS.PLAN_ID.eq(p.id())).fetch();
    }

    AttentionItem approvalItem(Plan p) {
        return attentionItems.findByKey("plan_approval:" + p.id()).orElseThrow();
    }

    static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new AssertionError("timed out waiting until " + what);
            Thread.sleep(100);
        }
    }

    @Test
    void aSmallSafePlanDispatchesOnItsOwn() throws Exception {
        Project p = project(true);

        Plan started = service.start(p.name(), "Build a TODO app. [[fake:plan]]", null);
        assertThat(started.status()).isEqualTo(Plan.PLANNING);

        Plan done = settled(started);
        assertThat(done.status()).isEqualTo(Plan.DISPATCHED);
        assertThat(done.askFirst()).isFalse();
        assertThat(done.runId()).isNotNull();
        assertThat(done.plan().data()).contains("Build auth module");
        assertThat(done.architecture()).contains("TODO app");
        List<TasksRecord> tasks = tasksOf(done);
        assertThat(tasks).hasSize(3).allSatisfy(t -> {
            assertThat(t.getProjectId()).isEqualTo(p.id());
            assertThat(t.getRepoId()).isNotNull();
        });
        assertThat(tasks).extracting(TasksRecord::getStatus).contains("ready");
        assertThat(attentionItems.findByKey("plan_approval:" + done.id())).isEmpty();
        assertThat(done.attentionId()).isNull();
    }

    @Test
    void aRiskyPlanAsksFirstAndApproveDispatches() throws Exception {
        Project p = project(true);

        Plan asking = settled(service.start(p.name(), "Build a TODO app. [[fake:plan-risky]]", null));

        assertThat(asking.status()).isEqualTo(Plan.ASKING);
        assertThat(asking.askFirst()).isTrue();
        assertThat(asking.reason()).isEqualTo("Build auth module is risky: touches the migrations");
        assertThat(tasksOf(asking)).isEmpty();
        AttentionItem item = approvalItem(asking);
        assertThat(asking.attentionId()).isEqualTo(item.id());
        assertThat(item.urgency()).isEqualTo(AttentionItem.URGENT);
        assertThat(item.kind()).isEqualTo(AttentionItem.PLAN_APPROVAL);
        assertThat(item.projectId()).isEqualTo(p.id());
        assertThat(item.title()).isEqualTo("Approve the plan for " + p.name()
                + "? Build auth module is risky: touches the migrations");
        assertThat(item.body()).contains("Build auth module (risky: touches the migrations)");
        assertThat(item.proposedAnswer()).isEqualTo("approve");

        attention.resolve(item.id(), "approve", "user");

        assertThat(fresh(asking).status()).isEqualTo(Plan.DISPATCHED);
        assertThat(tasksOf(asking)).hasSize(3);
        assertThat(attentionItems.findById(item.id()).orElseThrow().status()).isEqualTo(AttentionItem.RESOLVED);
    }

    @Test
    void approvingTwiceDispatchesOnce() throws Exception {
        Project p = project(true);
        Plan asking = settled(service.start(p.name(), "Build it. [[fake:plan-risky]]", null));
        AttentionItem item = approvalItem(asking);

        attention.resolve(item.id(), "approve", "user");
        attention.resolve(item.id(), "yes", "user");
        // A resolve that read the item while it was still open, and acts after the first one.
        tx.executeWithoutResult(s -> service.onResolve(item, "ok"));

        assertThat(tasksOf(asking)).hasSize(3);
        assertThat(fresh(asking).status()).isEqualTo(Plan.DISPATCHED);
    }

    @Test
    void dropLeavesNoTasks() throws Exception {
        Project p = project(true);
        Plan asking = settled(service.start(p.name(), "Build it. [[fake:plan-ask]]", null));
        assertThat(asking.reason()).isEqualTo("the request is ambiguous");

        attention.resolve(approvalItem(asking).id(), "drop", "user");

        assertThat(fresh(asking).status()).isEqualTo(Plan.DROPPED);
        assertThat(tasksOf(asking)).isEmpty();
        assertThat(plans.findRecent(p.id(), 10)).hasSize(1);
    }

    @Test
    void aBlankAnswerIsRefusedAndTheItemStaysOpen() throws Exception {
        Project p = project(true);
        Plan asking = settled(service.start(p.name(), "Build it. [[fake:plan-ask]]", null));
        AttentionItem item = approvalItem(asking);

        assertThatThrownBy(() -> attention.resolve(item.id(), "  ", "user"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("answer approve, drop, or what to change");

        assertThat(attentionItems.findById(item.id()).orElseThrow().status()).isEqualTo(AttentionItem.OPEN);
        assertThat(fresh(asking).status()).isEqualTo(Plan.ASKING);
    }

    @Test
    void aNoteRevisesThePlan() throws Exception {
        Project p = project(true);
        Plan asking = settled(service.start(p.name(), "Build it. [[fake:plan-risky]]", null));

        // The note reaches the planner's prompt: its marker makes the revised plan ask for its own reason.
        String note = "keep auth out of it [[fake:plan-ask]]";
        attention.resolve(approvalItem(asking).id(), note, "user");

        assertThat(fresh(asking).status()).isEqualTo(Plan.DROPPED);
        Plan revision = plans.findRecent(p.id(), 10).getFirst();
        assertThat(revision.id()).isNotEqualTo(asking.id());
        assertThat(revision.revisesId()).isEqualTo(asking.id());
        assertThat(revision.note()).isEqualTo(note);
        assertThat(revision.request()).isEqualTo(asking.request());
        Plan revised = settled(revision);
        assertThat(revised.status()).isEqualTo(Plan.ASKING);
        assertThat(revised.reason()).isEqualTo("the request is ambiguous");
        assertThat(approvalItem(revised).status()).isEqualTo(AttentionItem.OPEN);
        assertThat(tasksOf(asking)).isEmpty();
    }

    @Test
    void withoutEvidenceItAsksAndApprovePutsTheProposedCommandsInForce() throws Exception {
        Project p = project(false);

        Plan asking = settled(service.start(p.name(), "Build it. [[fake:plan]] [[fake:plan-evidence]]", null));

        assertThat(asking.status()).isEqualTo(Plan.ASKING);
        assertThat(asking.reason()).isEqualTo("no tests in force yet");
        assertThat(approvalItem(asking).body()).contains("sh test.sh");
        assertThat(spaceItems.activeConvention(p.id(), null, "evidence")).isEmpty();

        attention.resolve(approvalItem(asking).id(), "approve", "user");

        SpaceItem evidence = spaceItems.activeConvention(p.id(), null, "evidence").orElseThrow();
        assertThat(evidence.authorKind()).isEqualTo(SpaceItem.USER);
        assertThat(Json.mapper().readTree(evidence.body().data()).path("value"))
                .isEqualTo(Json.mapper().readTree("[\"sh test.sh\"]"));
        assertThat(fresh(asking).status()).isEqualTo(Plan.DISPATCHED);
        assertThat(tasksOf(asking)).hasSize(3);
    }

    @Test
    void approvingKeepsTheEvidenceTheProjectAlreadyHas() throws Exception {
        Project p = project(true);
        Plan asking = settled(service.start(p.name(), "Build it. [[fake:plan-risky]] [[fake:plan-evidence]]", null));
        UUID before = spaceItems.activeConvention(p.id(), null, "evidence").orElseThrow().id();

        attention.resolve(approvalItem(asking).id(), "approve", "user");

        assertThat(spaceItems.activeConvention(p.id(), null, "evidence").orElseThrow().id()).isEqualTo(before);
        assertThat(fresh(asking).status()).isEqualTo(Plan.DISPATCHED);
    }

    @Test
    void theRequestsMilestoneLinksEveryTask() throws Exception {
        Project p = project(true);
        SpaceItem beta = space.add(p.id(), SpaceItem.MILESTONE, "beta", SpaceService.Author.USER,
                OffsetDateTime.now().plusDays(10), null, null);

        Plan done = settled(service.start(p.name(), "Build it. [[fake:plan]]", beta.id().toString()));

        assertThat(done.status()).isEqualTo(Plan.DISPATCHED);
        assertThat(done.milestoneId()).isEqualTo(beta.id());
        assertThat(tasksOf(done)).hasSize(3).allSatisfy(t -> assertThat(t.getMilestoneId()).isEqualTo(beta.id()));

        // A milestone may also be named by its text.
        Plan byText = settled(service.start(p.name(), "More. [[fake:plan]]", "Beta"));
        assertThat(byText.milestoneId()).isEqualTo(beta.id());
    }

    @Test
    void aPlannersUnknownMilestoneIsIgnored() throws Exception {
        Project p = project(true);
        Project other = project(true);
        SpaceItem elsewhere = space.add(other.id(), SpaceItem.MILESTONE, "theirs", SpaceService.Author.USER, null,
                null, null);

        Plan unknown = settled(service.start(p.name(),
                "Build it. [[fake:plan-milestone:" + UUID.randomUUID() + "]]", null));
        Plan foreign = settled(service.start(p.name(), "Build it. [[fake:plan-milestone:" + elsewhere.id() + "]]",
                null));

        for (Plan done : List.of(unknown, foreign)) {
            assertThat(done.status()).isEqualTo(Plan.DISPATCHED);
            assertThat(done.milestoneId()).isNull();
            assertThat(tasksOf(done)).hasSize(3).allSatisfy(t -> assertThat(t.getMilestoneId()).isNull());
        }
    }

    @Test
    void aPlannersOwnMilestoneOfTheProjectLinksTheTasks() throws Exception {
        Project p = project(true);
        SpaceItem beta = space.add(p.id(), SpaceItem.MILESTONE, "beta", SpaceService.Author.USER, null, null, null);

        Plan done = settled(service.start(p.name(), "Build it. [[fake:plan-milestone:" + beta.id() + "]]", null));

        assertThat(done.milestoneId()).isEqualTo(beta.id());
        assertThat(tasksOf(done)).hasSize(3).allSatisfy(t -> assertThat(t.getMilestoneId()).isEqualTo(beta.id()));
    }

    @Test
    void aPlanLeftPlanningByARestartFails() throws Exception {
        Project p = project(true);
        Plan left = plans.insert(p.id(), "Build it.", null, null, null);

        service.failLeftovers();

        Plan failed = fresh(left);
        assertThat(failed.status()).isEqualTo(Plan.FAILED);
        assertThat(failed.failure()).isEqualTo("coordinator restarted");
        AttentionItem item = attentionItems.findByKey("plan_failed:" + left.id()).orElseThrow();
        assertThat(item.urgency()).isEqualTo(AttentionItem.NORMAL);
        assertThat(item.kind()).isEqualTo(AttentionItem.PLAN_FAILED);
        assertThat(item.title()).isEqualTo("Plan for " + p.name() + " failed: coordinator restarted");

        // Resolving a failed plan's item does nothing more than resolve it.
        attention.resolve(item.id(), "ok", "user");
        assertThat(fresh(left).status()).isEqualTo(Plan.FAILED);
    }

    @Test
    void aFailedPlannerFilesAnItem() throws Exception {
        Project p = project(true);

        Plan failed = settled(service.start(p.name(), "Build it. [[fake:error]]", null));

        assertThat(failed.status()).isEqualTo(Plan.FAILED);
        assertThat(failed.failure()).isEqualTo("failure");
        assertThat(attentionItems.findByKey("plan_failed:" + failed.id()).orElseThrow().title())
                .isEqualTo("Plan for " + p.name() + " failed: failure");
    }

    @Test
    void aPausedSchedulerRefusesToPlan() throws Exception {
        Project p = project(true);
        pause.pause("test", null);

        assertThatThrownBy(() -> service.start(p.name(), "Build it. [[fake:plan]]", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paused");
        assertThat(plans.findRecent(p.id(), 10)).isEmpty();
    }

    @Test
    void startRefusesWhatItCannotPlan() throws Exception {
        Project p = project(true);
        Project noRepo = projects.create("plan-norepo-" + UUID.randomUUID().toString().substring(0, 8), null);
        Project paused = project(true);
        projects.update(paused.id(), paused.name(), Project.PAUSED, null);

        assertThatThrownBy(() -> service.start("no-such-project", "x", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no such project");
        assertThatThrownBy(() -> service.start(paused.name(), "x", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paused");
        assertThatThrownBy(() -> service.start(noRepo.name(), "x", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("no GitHub repo");
        assertThatThrownBy(() -> service.start(p.name(), " ", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("request");
        assertThatThrownBy(() -> service.start(p.name(), "x", UUID.randomUUID().toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("milestone");
        assertThat(plans.findRecent(p.id(), 10)).isEmpty();
    }

    @Test
    void thePlannerSeesTheRepo() throws Exception {
        Project p = project(true);

        Plan done = settled(service.start(p.name(), "Build it. [[fake:plan]]", null));

        Path probe = RUNS.resolve(done.runId().toString()).resolve("claude-config").resolve("fake-probe.txt");
        assertThat(Files.readString(probe)).contains("repo=visible");
        assertThat(PLANS.resolve(done.id().toString()).resolve("repo").resolve("README.md")).exists();
    }
}
