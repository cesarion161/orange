package com.ai.orange.status;

import static com.ai.orange.db.jooq.Tables.SPACE_ITEMS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.brief.MilestoneTracks;
import com.ai.orange.brief.OnTrack;
import com.ai.orange.planner.Plan;
import com.ai.orange.planner.PlanRepository;
import com.ai.orange.project.Project;
import com.ai.orange.project.ProjectService;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.project.SpaceService;
import com.ai.orange.project.SpaceService.Author;
import com.ai.orange.project.TaskPlacement;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.status.StatusReport.ProjectStatus;
import com.ai.orange.status.StatusReport.TaskLine;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** What is in flight, by project: plans, unfinished tasks (stuck first), milestones and what waits. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StatusServiceIT {

    @Autowired StatusService status;
    @Autowired ProjectService projects;
    @Autowired SpaceService space;
    @Autowired TaskService taskService;
    @Autowired TaskEventRepository taskEvents;
    @Autowired PlanRepository plans;
    @Autowired AttentionService attention;
    @Autowired SchedulerPause pause;
    @Autowired DSLContext dsl;

    @AfterEach
    void resume() {
        pause.resume();
    }

    Project project() {
        return projects.create("status-" + UUID.randomUUID().toString().substring(0, 8), null);
    }

    Task task(Project p, String title, UUID milestoneId, String status) {
        Task t = taskService.createGraph(List.of(new TaskDef("t-" + UUID.randomUUID(), title, null, null, null, null,
                p == null ? TaskPlacement.NONE : new TaskPlacement(p.id(), null), null, milestoneId)), List.of()).get(0);
        dsl.update(TASKS).set(TASKS.STATUS, status).where(TASKS.ID.eq(t.id())).execute();
        return t;
    }

    ProjectStatus of(StatusReport report, Project p) {
        return report.projects().stream().filter(s -> s.id().equals(p.id().toString())).findFirst().orElseThrow();
    }

    static int total(java.util.Map<String, Integer> counts) {
        return counts.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void tasksOfNoProjectAreCountedBeforeTheLinesAreCut() {
        StatusReport before = status.report();
        List<UUID> made = new java.util.ArrayList<>();
        for (int i = 0; i < StatusService.MAX_TASKS + 2; i++) made.add(task(null, "Unplaced " + i, null, "running").id());

        StatusReport after = status.report();
        // The other tests read the tasks of no project too: these end here.
        dsl.update(TASKS).set(TASKS.STATUS, "cancelled").where(TASKS.ID.in(made)).execute();

        assertThat(after.unplaced()).hasSizeLessThanOrEqualTo(StatusService.MAX_TASKS);
        assertThat(total(after.unplacedByState()) - total(before.unplacedByState()))
                .isEqualTo(StatusService.MAX_TASKS + 2);
        int inFlight = total(after.unplacedByState()) - after.unplacedByState().getOrDefault("needs_human", 0);
        // The start context says the full count (read alone: the suite's other projects may fill its lines).
        StatusReport alone = new StatusReport(after.at(), null, List.of(), 0, 0, 0, after.unplaced(),
                after.unplacedByState());
        assertThat(StatusText.startContext(alone)).contains("no project: " + inFlight + " in flight");
    }

    @Test
    void theReportReadsInOneRepeatableReadTransaction() throws Exception {
        // report() is what callers call: it must carry the transaction itself, not through a self-call.
        org.springframework.transaction.annotation.Transactional tx = StatusService.class.getMethod("report")
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).isEqualTo(org.springframework.transaction.annotation.Isolation.REPEATABLE_READ);
    }

    @Test
    void groupsTasksPlansAndMilestonesByProject() {
        Project a = project();
        Project b = project();
        OffsetDateTime now = OffsetDateTime.now();
        SpaceItem beta = space.add(a.id(), SpaceItem.MILESTONE, "Beta", Author.USER, now.plusDays(5), null, null);
        dsl.update(SPACE_ITEMS).set(SPACE_ITEMS.CREATED_AT, now.minusDays(5)).where(SPACE_ITEMS.ID.eq(beta.id())).execute();
        Task running = task(a, "Build the API", beta.id(), "running");
        task(a, "Write docs", null, "ready");
        Task merged = task(a, "Set up CI", beta.id(), "integrated");
        taskEvents.append(merged.id(), "orange", "integrated", JSONB.valueOf("{}"));
        task(a, "Old thing", null, "cancelled");
        task(b, "Other work", null, "pending");
        Task loose = task(null, "A task of no project " + UUID.randomUUID(), null, "running");
        Plan planning = plans.insert(a.id(), "Add a login page", null, null, null);
        Plan asking = plans.insert(a.id(), "Rewrite the storage", null, null, null);
        plans.finishPlanning(asking.id(), Plan.ASKING, true, "it touches the migrations", null, null, null, null, null);
        attention.raise(AttentionItem.PLAN_APPROVAL, "plan_approval:" + asking.id(), AttentionItem.URGENT, a.id(), null,
                null, "Approve the plan?", "body", "approve");

        StatusReport report = status.report();

        ProjectStatus sa = of(report, a);
        assertThat(sa.name()).isEqualTo(a.name());
        assertThat(sa.plans()).extracting(StatusReport.PlanLine::id)
                .containsExactly(planning.id().toString(), asking.id().toString());
        assertThat(sa.plans()).extracting(StatusReport.PlanLine::status).containsExactly(Plan.PLANNING, Plan.ASKING);
        assertThat(sa.plans().get(1).reason()).isEqualTo("it touches the migrations");
        assertThat(sa.tasksByState()).containsExactly(entry("running", 1), entry("ready", 1));
        assertThat(sa.tasks()).extracting(TaskLine::title).containsExactly("Build the API", "Write docs");
        TaskLine first = sa.tasks().getFirst();
        assertThat(first.id8()).isEqualTo(running.shortId());
        assertThat(first.milestone()).isEqualTo("Beta");
        assertThat(sa.integratedToday()).isEqualTo(1);
        assertThat(sa.openAttention()).isEqualTo(1);
        assertThat(sa.milestones()).hasSize(1);
        assertThat(sa.milestones().getFirst().text()).isEqualTo("Beta");
        assertThat(sa.milestones().getFirst().tasks()).containsEntry(MilestoneTracks.MERGED, 1)
                .containsEntry(MilestoneTracks.RUNNING, 1);
        assertThat(sa.milestones().getFirst().track()).isEqualTo(OnTrack.ON_TRACK);

        ProjectStatus sb = of(report, b);
        assertThat(sb.tasks()).extracting(TaskLine::title).containsExactly("Other work");
        assertThat(sb.plans()).isEmpty();
        assertThat(sb.milestones()).isEmpty();

        assertThat(report.unplaced()).extracting(TaskLine::title).contains(loose.title());
        assertThat(report.openAttention()).isGreaterThanOrEqualTo(1);
        assertThat(report.urgentAttention()).isGreaterThanOrEqualTo(1);
        assertThat(report.pause()).isNull();
    }

    @Test
    void stuckTasksComeFirst() {
        Project p = project();
        for (int i = 0; i < 11; i++) task(p, "Ready " + i, null, "ready");
        task(p, "Running", null, "verifying");
        Task stuck = task(p, "Stuck", null, "needs_human");

        ProjectStatus s = of(status.report(), p);

        assertThat(s.tasks()).hasSize(10);
        assertThat(s.tasks().getFirst().id8()).isEqualTo(stuck.shortId());
        assertThat(s.tasks().getFirst().status()).isEqualTo("needs_human");
        assertThat(s.tasks().get(1).title()).isEqualTo("Running");
        assertThat(s.tasksByState()).containsExactly(entry("needs_human", 1), entry("verifying", 1), entry("ready", 11));
    }

    @Test
    void aPausedSchedulerShows() {
        pause.pause("user: lunch", null);

        StatusReport report = status.report();

        assertThat(report.pause()).isNotNull();
        assertThat(report.pause().reason()).isEqualTo("user: lunch");
        assertThat(report.pause().until()).isNull();
        assertThat(StatusText.startContext(report).lines().findFirst().orElseThrow()).contains("paused")
                .contains("user: lunch");
        assertThat(StatusText.text(report)).contains("user: lunch");
    }
}
