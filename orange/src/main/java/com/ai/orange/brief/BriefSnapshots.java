package com.ai.orange.brief;

import static com.ai.orange.brief.BriefSnapshot.one;
import static com.ai.orange.db.jooq.Tables.ATTENTION_ITEMS;
import static com.ai.orange.db.jooq.Tables.PROJECTS;
import static com.ai.orange.db.jooq.Tables.SESSIONS;
import static com.ai.orange.db.jooq.Tables.SESSION_EVENTS;
import static com.ai.orange.db.jooq.Tables.SESSION_WORK;
import static com.ai.orange.db.jooq.Tables.SPACE_ITEMS;
import static com.ai.orange.db.jooq.Tables.TASKS;
import static com.ai.orange.db.jooq.Tables.TASK_EVENTS;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionProperties;
import com.ai.orange.brief.BriefSnapshot.Closed;
import com.ai.orange.brief.BriefSnapshot.Item;
import com.ai.orange.brief.BriefSnapshot.Milestone;
import com.ai.orange.brief.BriefSnapshot.ProjectFacts;
import com.ai.orange.brief.BriefSnapshot.SessionFacts;
import com.ai.orange.brief.BriefSnapshot.TaskFacts;
import com.ai.orange.json.Json;
import com.ai.orange.project.Conventions;
import com.ai.orange.project.Project;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.session.NeutralEvent;
import com.ai.orange.session.Session;
import com.ai.orange.session.SessionWork;
import com.ai.orange.task.TaskStatus;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the facts of a brief (project-space spec §7): where each active project stands, what
 * waits on the user, where the user's attention went and each project's rhythm. Everything is a
 * query; nothing here is a model's opinion. The caps on {@link BriefSnapshot} bound what is read
 * into the snapshot, and every text is flattened to one line and cut.
 */
@Repository
public class BriefSnapshots {

    /** The statuses of a task that is still on its way, in the order the brief names them. */
    private static final List<TaskStatus> OPEN_TASKS = List.of(TaskStatus.PENDING, TaskStatus.READY,
            TaskStatus.RUNNING, TaskStatus.VERIFYING, TaskStatus.VERIFIED, TaskStatus.NEEDS_HUMAN);

    /**
     * The days, in the user's zone, on which a project had a prompt or a stop. The zone is a name
     * Postgres knows plus seconds to add: a fixed offset goes in as UTC and its seconds, because
     * Postgres reads an offset written as a zone name with the opposite sign.
     */
    private static final String ACTIVE_DAYS = """
            SELECT s.project_id,
                   CAST((e.received_at AT TIME ZONE CAST(? AS text))
                        + CAST(? AS integer) * INTERVAL '1 second' AS date) AS day
            FROM session_events e JOIN sessions s ON s.id = e.session_id
            WHERE e.type IN ('user_prompt', 'stopped') AND e.received_at > CAST(? AS timestamptz)
              AND s.project_id IS NOT NULL
            GROUP BY 1, 2
            """;

    private final DSLContext dsl;
    private final Conventions conventions;
    private final AttentionProperties attention;

    public BriefSnapshots(DSLContext dsl, Conventions conventions, AttentionProperties attention) {
        this.dsl = dsl;
        this.conventions = conventions;
        this.attention = attention;
    }

    private record ProjectRow(UUID id, String name, String summary) {}

    private record SpaceRow(String text, OffsetDateTime dueAt, OffsetDateTime createdAt) {}

    private record Focus(int prompts, OffsetDateTime lastPromptAt) {}

    /**
     * The facts as of {@code now}, which is in the user's zone: days are counted there.
     *
     * @param since the start of what the brief covers (closed work, prompts, integrated tasks)
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public BriefSnapshot read(OffsetDateTime since, ZonedDateTime now) {
        ZoneId zone = now.getZone();
        OffsetDateTime at = now.toOffsetDateTime();
        LocalDate today = now.toLocalDate();

        List<ProjectRow> active = activeProjects();
        List<ProjectRow> shown = active.subList(0, Math.min(active.size(), BriefSnapshot.MAX_PROJECTS));
        List<String> others = active.subList(shown.size(), active.size()).stream()
                .map(p -> one(p.name(), BriefSnapshot.TITLE)).toList();
        List<UUID> ids = shown.stream().map(ProjectRow::id).toList();

        Map<UUID, List<SpaceRow>> goals = new HashMap<>();
        Map<UUID, List<SpaceRow>> milestones = new HashMap<>();
        readSpace(ids, goals, milestones);
        Map<UUID, List<SessionFacts>> sessions = sessions(ids, at);
        Map<UUID, List<Closed>> closed = closed(ids, since);
        Map<UUID, List<Item>> waiting = new HashMap<>();
        List<Item> elsewhere = new ArrayList<>();
        readWaiting(Set.copyOf(ids), waiting, elsewhere);
        Map<UUID, Focus> focus = new HashMap<>();
        int promptsTotal = readFocus(since, focus);
        Map<UUID, List<LocalDate>> activeDays = activeDays(zone, at);

        List<ProjectFacts> projects = new ArrayList<>();
        for (ProjectRow p : shown) {
            List<SessionFacts> itsSessions = sessions.getOrDefault(p.id(), List.of());
            List<Closed> itsClosed = closed.getOrDefault(p.id(), List.of());
            List<Item> itsWaiting = waiting.getOrDefault(p.id(), List.of());
            Focus itsFocus = focus.getOrDefault(p.id(), new Focus(0, null));
            Quiet quiet = Quiet.of(activeDays.getOrDefault(p.id(), List.of()), today,
                    Quiet.cadenceDays(conventions.resolve(Quiet.CADENCE, p.id(), null).orElse(null)));
            projects.add(new ProjectFacts(p.id().toString(), one(p.name(), BriefSnapshot.TITLE),
                    one(p.summary(), BriefSnapshot.TEXT),
                    goals.getOrDefault(p.id(), List.of()).stream().limit(BriefSnapshot.MAX_GOALS)
                            .map(SpaceRow::text).toList(),
                    milestones(p.id(), milestones.getOrDefault(p.id(), List.of()), at, today, zone),
                    capped(itsSessions, BriefSnapshot.MAX_SESSIONS), itsSessions.size(),
                    capped(itsClosed, BriefSnapshot.MAX_CLOSED),
                    count(itsClosed, SessionWork.INTEGRATED), count(itsClosed, SessionWork.VERIFIED),
                    capped(itsWaiting, BriefSnapshot.MAX_WAITING), itsWaiting.size(),
                    (int) itsWaiting.stream().filter(i -> !AttentionItem.NORMAL.equals(i.urgency())).count(),
                    itsFocus.prompts(), text(itsFocus.lastPromptAt()), quiet));
        }
        return new BriefSnapshot(at.toString(), since.toString(), zone.getId(), projects, others,
                dsl.fetchCount(PROJECTS, PROJECTS.STATUS.eq(Project.PROPOSED)),
                dsl.fetchCount(PROJECTS, PROJECTS.STATUS.eq(Project.PAUSED)),
                promptsTotal, capped(elsewhere, BriefSnapshot.MAX_ELSEWHERE), elsewhere.size(), tasks(since));
    }

    /** Active projects, the one heard from last first; those with no session at all come last, by name. */
    private List<ProjectRow> activeProjects() {
        Map<UUID, OffsetDateTime> lastHeard = new HashMap<>();
        dsl.select(SESSIONS.PROJECT_ID, DSL.max(SESSIONS.LAST_EVENT_AT)).from(SESSIONS)
                .where(SESSIONS.PROJECT_ID.isNotNull()).groupBy(SESSIONS.PROJECT_ID)
                .forEach(r -> lastHeard.put(r.value1(), r.value2()));
        List<ProjectRow> rows = new ArrayList<>(dsl.select(PROJECTS.ID, PROJECTS.NAME, PROJECTS.SUMMARY)
                .from(PROJECTS).where(PROJECTS.STATUS.eq(Project.ACTIVE))
                .fetch(r -> new ProjectRow(r.value1(), r.value2(), r.value3())));
        rows.sort(Comparator
                .comparing((ProjectRow p) -> lastHeard.get(p.id()),
                        Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder()))
                .thenComparing(ProjectRow::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(ProjectRow::name));
        return rows;
    }

    /** The projects' own active goals (newest first) and milestones (the nearest date first, undated last). */
    private void readSpace(List<UUID> ids, Map<UUID, List<SpaceRow>> goals, Map<UUID, List<SpaceRow>> milestones) {
        dsl.select(SPACE_ITEMS.PROJECT_ID, SPACE_ITEMS.KIND, SPACE_ITEMS.BODY, SPACE_ITEMS.DUE_AT,
                        SPACE_ITEMS.CREATED_AT)
                .from(SPACE_ITEMS)
                .where(SPACE_ITEMS.PROJECT_ID.in(ids)
                        .and(SPACE_ITEMS.STATUS.eq(SpaceItem.ACTIVE))
                        .and(SPACE_ITEMS.SESSION_ID.isNull())
                        .and(SPACE_ITEMS.KIND.in(SpaceItem.GOAL, SpaceItem.MILESTONE)))
                .orderBy(SPACE_ITEMS.CREATED_AT.desc(), SPACE_ITEMS.ID.desc())
                .forEach(r -> {
                    SpaceRow row = new SpaceRow(one(bodyText(r.value3().data()), BriefSnapshot.TEXT), r.value4(),
                            r.value5());
                    (SpaceItem.GOAL.equals(r.value2()) ? goals : milestones)
                            .computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(row);
                });
        Comparator<SpaceRow> byDate = Comparator
                .comparing(SpaceRow::dueAt, Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(SpaceRow::createdAt);
        milestones.values().forEach(rows -> rows.sort(byDate));
    }

    private List<Milestone> milestones(UUID projectId, List<SpaceRow> rows, OffsetDateTime at, LocalDate today,
                                       ZoneId zone) {
        List<Milestone> out = new ArrayList<>();
        for (SpaceRow m : rows.subList(0, Math.min(rows.size(), BriefSnapshot.MAX_MILESTONES))) {
            if (m.dueAt() == null) {
                out.add(new Milestone(m.text(), null, null, null, false, null));
                continue;
            }
            int daysLeft = (int) ChronoUnit.DAYS.between(today, m.dueAt().atZoneSameInstant(zone).toLocalDate());
            long whole = Duration.between(m.createdAt(), m.dueAt()).toMillis();
            long gone = Duration.between(m.createdAt(), at).toMillis();
            int elapsed = whole <= 0 ? 100 : (int) Math.clamp(100 * gone / whole, 0, 100);
            // Not linked to the milestone: nothing ties a piece of work to one (Phase D ruling 9).
            int closedSince = dsl.fetchCount(dsl.selectOne().from(SESSION_WORK)
                    .join(SESSIONS).on(SESSIONS.ID.eq(SESSION_WORK.SESSION_ID))
                    .where(SESSIONS.PROJECT_ID.eq(projectId)
                            .and(SESSION_WORK.STATUS.eq(SessionWork.CLOSED))
                            .and(SESSION_WORK.CLOSED_AT.gt(m.createdAt()))));
            out.add(new Milestone(m.text(), m.dueAt().toString(), daysLeft, elapsed, m.dueAt().isBefore(at),
                    closedSince));
        }
        return out;
    }

    /** Sessions that have not ended and were heard from lately, the latest first. */
    private Map<UUID, List<SessionFacts>> sessions(List<UUID> ids, OffsetDateTime at) {
        Map<UUID, List<SessionFacts>> out = new HashMap<>();
        dsl.select(SESSIONS.PROJECT_ID, SESSIONS.STATUS, SESSIONS.WAITING_REASON, SESSIONS.BRANCH, SESSIONS.INTENT,
                        SESSIONS.LAST_EVENT_AT, SESSIONS.WAITING_SINCE)
                .from(SESSIONS)
                .where(SESSIONS.PROJECT_ID.in(ids)
                        .and(SESSIONS.STATUS.ne(Session.ENDED))
                        .and(SESSIONS.LAST_EVENT_AT.gt(at.minusDays(BriefSnapshot.SESSION_DAYS))))
                .orderBy(SESSIONS.LAST_EVENT_AT.desc(), SESSIONS.ID.desc())
                .forEach(r -> out.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(new SessionFacts(
                        BriefSnapshot.state(r.value2(), r.value3(), Duration.between(r.value6(), at),
                                attention.staleAfter()),
                        one(r.value4(), BriefSnapshot.TITLE), one(r.value5(), BriefSnapshot.TEXT),
                        text(r.value6()), text(r.value7()))));
        return out;
    }

    /** Work closed since the last brief, the latest first. */
    private Map<UUID, List<Closed>> closed(List<UUID> ids, OffsetDateTime since) {
        Map<UUID, List<Closed>> out = new HashMap<>();
        dsl.select(SESSIONS.PROJECT_ID, SESSION_WORK.RESULT, SESSION_WORK.INTENT, SESSION_WORK.CLOSED_AT)
                .from(SESSION_WORK).join(SESSIONS).on(SESSIONS.ID.eq(SESSION_WORK.SESSION_ID))
                .where(SESSIONS.PROJECT_ID.in(ids)
                        .and(SESSION_WORK.STATUS.eq(SessionWork.CLOSED))
                        .and(SESSION_WORK.CLOSED_AT.gt(since)))
                .orderBy(SESSION_WORK.CLOSED_AT.desc(), SESSION_WORK.ID.desc())
                .forEach(r -> out.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(
                        new Closed(r.value2(), one(r.value3(), BriefSnapshot.TEXT), text(r.value4()))));
        return out;
    }

    /** Open attention items, most urgent first, then oldest first: by project, or elsewhere. */
    private void readWaiting(Set<UUID> shown, Map<UUID, List<Item>> waiting, List<Item> elsewhere) {
        dsl.select(ATTENTION_ITEMS.PROJECT_ID, ATTENTION_ITEMS.KIND, ATTENTION_ITEMS.URGENCY, ATTENTION_ITEMS.TITLE,
                        ATTENTION_ITEMS.CREATED_AT)
                .from(ATTENTION_ITEMS)
                .where(ATTENTION_ITEMS.STATUS.eq(AttentionItem.OPEN))
                .orderBy(DSL.case_(ATTENTION_ITEMS.URGENCY).when(AttentionItem.CRITICAL, 0)
                                .when(AttentionItem.URGENT, 1).else_(2).asc(),
                        ATTENTION_ITEMS.CREATED_AT.asc(), ATTENTION_ITEMS.ID.asc())
                .forEach(r -> {
                    Item item = new Item(r.value2(), r.value3(), one(r.value4(), BriefSnapshot.TITLE),
                            text(r.value5()));
                    if (r.value1() != null && shown.contains(r.value1())) {
                        waiting.computeIfAbsent(r.value1(), k -> new ArrayList<>()).add(item);
                    } else {
                        elsewhere.add(item);
                    }
                });
    }

    /** The user's prompts since the last brief, by project. Returns them all, whatever their session's project. */
    private int readFocus(OffsetDateTime since, Map<UUID, Focus> focus) {
        int[] total = {0};
        dsl.select(SESSIONS.PROJECT_ID, DSL.count(), DSL.max(SESSION_EVENTS.RECEIVED_AT))
                .from(SESSION_EVENTS).join(SESSIONS).on(SESSIONS.ID.eq(SESSION_EVENTS.SESSION_ID))
                .where(SESSION_EVENTS.TYPE.eq(NeutralEvent.USER_PROMPT).and(SESSION_EVENTS.RECEIVED_AT.gt(since)))
                .groupBy(SESSIONS.PROJECT_ID)
                .forEach(r -> {
                    total[0] += r.value2();
                    if (r.value1() != null) focus.put(r.value1(), new Focus(r.value2(), r.value3()));
                });
        return total[0];
    }

    private Map<UUID, List<LocalDate>> activeDays(ZoneId zone, OffsetDateTime at) {
        boolean fixed = zone.normalized() instanceof ZoneOffset;
        String zoneName = fixed ? "UTC" : zone.getId();
        int seconds = fixed ? ((ZoneOffset) zone.normalized()).getTotalSeconds() : 0;
        Map<UUID, List<LocalDate>> out = new HashMap<>();
        dsl.resultQuery(ACTIVE_DAYS, zoneName, seconds, at.minusDays(BriefSnapshot.HISTORY_DAYS))
                .forEach(r -> out.computeIfAbsent(r.get(0, UUID.class), k -> new ArrayList<>())
                        .add(r.get(1, LocalDate.class)));
        return out;
    }

    /** orange's own tasks: they have no project yet (Phase D ruling 10). */
    private TaskFacts tasks(OffsetDateTime since) {
        Map<String, Integer> counted = new HashMap<>();
        dsl.select(TASKS.STATUS, DSL.count()).from(TASKS)
                .where(TASKS.STATUS.in(OPEN_TASKS.stream().map(TaskStatus::dbValue).toList()))
                .groupBy(TASKS.STATUS)
                .forEach(r -> counted.put(r.value1(), r.value2()));
        Map<String, Integer> byStatus = new LinkedHashMap<>();
        for (TaskStatus status : OPEN_TASKS) {
            Integer n = counted.get(status.dbValue());
            if (n != null && n > 0) byStatus.put(status.dbValue(), n);
        }
        int integrated = dsl.fetchCount(TASK_EVENTS,
                TASK_EVENTS.EVENT_TYPE.eq("integrated").and(TASK_EVENTS.CREATED_AT.gt(since)));
        List<String> needsHuman = dsl.select(TASKS.TITLE).from(TASKS)
                .where(TASKS.STATUS.eq(TaskStatus.NEEDS_HUMAN.dbValue()))
                .orderBy(TASKS.CREATED_AT.asc(), TASKS.ID.asc()).limit(BriefSnapshot.MAX_NEEDS_HUMAN)
                .fetch(r -> one(r.value1(), BriefSnapshot.TITLE));
        return new TaskFacts(byStatus, integrated, needsHuman);
    }

    private static int count(List<Closed> closed, String result) {
        return (int) closed.stream().filter(c -> result.equals(c.result())).count();
    }

    private static <T> List<T> capped(List<T> all, int max) {
        return all.size() <= max ? all : List.copyOf(all.subList(0, max));
    }

    private static String text(OffsetDateTime time) {
        return time == null ? null : time.toString();
    }

    /** The text of a space item's body; empty when the body holds none. */
    private static String bodyText(String json) {
        try {
            return Json.mapper().readTree(json).path("text").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
