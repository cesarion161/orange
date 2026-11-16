package com.ai.orange.status;

import static com.ai.orange.db.jooq.Tables.ATTENTION_ITEMS;
import static com.ai.orange.db.jooq.Tables.PROJECTS;
import static com.ai.orange.db.jooq.Tables.RUNS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.brief.BriefProperties;
import com.ai.orange.brief.BriefSnapshot.Milestone;
import com.ai.orange.brief.MilestoneTracks;
import com.ai.orange.notify.NotifyProperties;
import com.ai.orange.planner.Plan;
import com.ai.orange.planner.PlanRepository;
import com.ai.orange.project.Project;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.status.StatusReport.Pause;
import com.ai.orange.status.StatusReport.PlanLine;
import com.ai.orange.status.StatusReport.ProjectStatus;
import com.ai.orange.status.StatusReport.TaskLine;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskStatus;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads what is in flight (remote-first spec §7.5): for each active project its plans in flight, its
 * unfinished tasks, what was integrated today, its milestones ({@link MilestoneTracks}) and what of it
 * waits for the user; besides, the pause, the running runs and the tasks of no project.
 */
@Service
public class StatusService {

    /** At most this many task lines per project, and of tasks of no project. */
    public static final int MAX_TASKS = 10;
    private static final int MAX_TEXT = 160;

    /** Unfinished statuses, in the order they are shown: what wants a person first. */
    static final List<TaskStatus> UNFINISHED = List.of(TaskStatus.NEEDS_HUMAN, TaskStatus.RUNNING,
            TaskStatus.VERIFYING, TaskStatus.VERIFIED, TaskStatus.READY, TaskStatus.PENDING);

    private final DSLContext dsl;
    private final SchedulerPause pause;
    private final PlanRepository plans;
    private final MilestoneTracks tracks;
    private final BriefProperties brief;
    private final NotifyProperties notify;

    public StatusService(DSLContext dsl, SchedulerPause pause, PlanRepository plans, MilestoneTracks tracks,
                         BriefProperties brief, NotifyProperties notify) {
        this.dsl = dsl;
        this.pause = pause;
        this.plans = plans;
        this.tracks = tracks;
        this.brief = brief;
        this.notify = notify;
    }

    private record TaskRow(UUID id, UUID projectId, String title, TaskStatus status, UUID milestoneId,
                           OffsetDateTime createdAt) {}

    /** The status now, days counted in the user's zone ({@code orange.notify.zone}). */
    public StatusReport report() {
        return report(ZonedDateTime.now(notify.zoneId()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatusReport report(ZonedDateTime now) {
        OffsetDateTime at = now.toOffsetDateTime();
        Pause paused = pause.current()
                .map(p -> new Pause(p.reason(), text(p.until()), text(p.since())))
                .orElse(null);

        Map<UUID, String> active = new LinkedHashMap<>();
        dsl.select(PROJECTS.ID, PROJECTS.NAME).from(PROJECTS).where(PROJECTS.STATUS.eq(Project.ACTIVE))
                .orderBy(PROJECTS.NAME.asc(), PROJECTS.ID.asc())
                .forEach(r -> active.put(r.value1(), r.value2()));

        Map<UUID, List<PlanLine>> planLines = new HashMap<>();
        for (Plan p : plans.findByStatus(List.of(Plan.PLANNING, Plan.ASKING))) {
            planLines.computeIfAbsent(p.projectId(), k -> new ArrayList<>()).add(new PlanLine(p.id().toString(),
                    p.status(), oneLine(p.request()), p.reason() == null ? null : oneLine(p.reason()),
                    text(p.createdAt())));
        }

        Map<UUID, List<TaskRow>> tasksByProject = new HashMap<>();
        List<TaskRow> unplaced = new ArrayList<>();
        dsl.select(TASKS.ID, TASKS.PROJECT_ID, TASKS.TITLE, TASKS.STATUS, TASKS.MILESTONE_ID, TASKS.CREATED_AT)
                .from(TASKS)
                .where(TASKS.STATUS.in(UNFINISHED.stream().map(TaskStatus::dbValue).toList()))
                .forEach(r -> {
                    TaskRow t = new TaskRow(r.value1(), r.value2(), r.value3(), TaskStatus.fromDb(r.value4()),
                            r.value5(), r.value6());
                    if (t.projectId() == null) unplaced.add(t);
                    else if (active.containsKey(t.projectId())) {
                        tasksByProject.computeIfAbsent(t.projectId(), k -> new ArrayList<>()).add(t);
                    }
                });

        Map<UUID, Integer> integratedToday = new HashMap<>();
        if (!active.isEmpty()) {
            OffsetDateTime dayStart = now.toLocalDate().atStartOfDay(now.getZone()).toOffsetDateTime();
            dsl.select(TASKS.PROJECT_ID, DSL.countDistinct(TASKS.ID)).from(TASK_EVENTS)
                    .join(TASKS).on(TASKS.ID.eq(TASK_EVENTS.TASK_ID))
                    .where(TASKS.PROJECT_ID.in(active.keySet())
                            .and(TASK_EVENTS.EVENT_TYPE.eq("integrated"))
                            .and(TASK_EVENTS.CREATED_AT.ge(dayStart)))
                    .groupBy(TASKS.PROJECT_ID)
                    .forEach(r -> integratedToday.put(r.value1(), r.value2()));
        }

        // Unresolved: an item the user acked still waits for an answer.
        Map<UUID, Integer> attentionByProject = new HashMap<>();
        int[] open = {0};
        int[] urgent = {0};
        dsl.select(ATTENTION_ITEMS.PROJECT_ID, ATTENTION_ITEMS.URGENCY, DSL.count()).from(ATTENTION_ITEMS)
                .where(ATTENTION_ITEMS.STATUS.ne(AttentionItem.RESOLVED))
                .groupBy(ATTENTION_ITEMS.PROJECT_ID, ATTENTION_ITEMS.URGENCY)
                .forEach(r -> {
                    open[0] += r.value3();
                    if (AttentionItem.URGENT.equals(r.value2()) || AttentionItem.CRITICAL.equals(r.value2())) {
                        urgent[0] += r.value3();
                    }
                    if (r.value1() != null) attentionByProject.merge(r.value1(), r.value3(), Integer::sum);
                });

        int runningRuns = dsl.fetchCount(RUNS, RUNS.STATUS.eq(RunStatus.RUNNING.dbValue()));

        Map<UUID, List<Milestone>> milestones = tracks.read(active.keySet(), at, now.toLocalDate(), now.getZone(),
                brief.onTrackMargin());
        Map<UUID, Optional<String>> milestoneText = new HashMap<>();

        List<ProjectStatus> projects = new ArrayList<>();
        for (Map.Entry<UUID, String> p : active.entrySet()) {
            UUID id = p.getKey();
            List<TaskRow> rows = tasksByProject.getOrDefault(id, List.of());
            List<Milestone> own = milestones.getOrDefault(id, List.of());
            List<TaskLine> lines = lines(rows, t -> milestoneText.computeIfAbsent(t.milestoneId(),
                    m -> tracks.current(m).flatMap(current -> own.stream()
                            .filter(x -> x.id().equals(current.toString())).map(Milestone::text).findFirst())));
            projects.add(new ProjectStatus(id.toString(), p.getValue(), planLines.getOrDefault(id, List.of()),
                    counts(rows), lines, integratedToday.getOrDefault(id, 0), own,
                    attentionByProject.getOrDefault(id, 0)));
        }
        projects.sort(Comparator.comparingInt(StatusService::rank)
                .thenComparing(ProjectStatus::name, String.CASE_INSENSITIVE_ORDER));

        return new StatusReport(at.toString(), paused, projects, open[0], urgent[0], runningRuns,
                lines(unplaced, t -> Optional.empty()));
    }

    /** What wants the user first, then what is in flight, then the quiet ones. */
    private static int rank(ProjectStatus p) {
        if (p.openAttention() > 0 || p.tasksByState().containsKey(TaskStatus.NEEDS_HUMAN.dbValue())) return 0;
        if (!p.tasksByState().isEmpty() || !p.plans().isEmpty()) return 1;
        return 2;
    }

    private static Map<String, Integer> counts(List<TaskRow> rows) {
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (TaskStatus s : UNFINISHED) {
            int n = (int) rows.stream().filter(t -> t.status() == s).count();
            if (n > 0) byStatus.put(s.dbValue(), n);
        }
        return byStatus;
    }

    private static List<TaskLine> lines(List<TaskRow> rows, java.util.function.Function<TaskRow, Optional<String>> milestone) {
        return rows.stream()
                .sorted(Comparator.comparingInt((TaskRow t) -> UNFINISHED.indexOf(t.status()))
                        .thenComparing(TaskRow::createdAt).thenComparing(TaskRow::id))
                .limit(MAX_TASKS)
                .map(t -> new TaskLine(Task.shortId(t.id()), oneLine(t.title()), t.status().dbValue(),
                        t.milestoneId() == null ? null : milestone.apply(t).orElse(null)))
                .toList();
    }

    private static String text(OffsetDateTime t) {
        return t == null ? null : t.toString();
    }

    /** One line of at most {@link #MAX_TEXT} characters. */
    static String oneLine(String s) {
        if (s == null) return "";
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX_TEXT ? flat : flat.substring(0, MAX_TEXT - 1) + "…";
    }
}
