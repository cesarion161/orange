package com.ai.orange.brief;

import static com.ai.orange.brief.BriefSnapshot.one;
import static com.ai.orange.db.jooq.Tables.SESSIONS;
import static com.ai.orange.db.jooq.Tables.SESSION_WORK;
import static com.ai.orange.db.jooq.Tables.SPACE_ITEMS;
import static com.ai.orange.db.jooq.Tables.TASKS;

import com.ai.orange.brief.BriefSnapshot.Milestone;
import com.ai.orange.json.Json;
import com.ai.orange.project.SpaceItem;
import com.ai.orange.session.SessionWork;
import com.ai.orange.task.TaskStatus;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

/**
 * Milestones and their tasks (remote-first spec §7.6). A planned task remembers the milestone item it
 * was planned for ({@code tasks.milestone_id}); editing a milestone supersedes that item with a new
 * one, so a milestone's tasks are those of every item on its supersede chain. A dropped (or done)
 * milestone is not current, and its tasks count for none.
 *
 * <p>Read by the brief, {@code orange_status} and the steering tab's start context, so they say the
 * same thing: for each active milestone of a project, its tasks by state, and for a dated one with
 * tasks orange's label ({@link OnTrack}).
 */
@Repository
public class MilestoneTracks {

    /** A milestone's task states, in the order they are shown. */
    public static final String MERGED = "merged";
    public static final String RUNNING = "running";
    public static final String STUCK = "stuck";
    public static final String NOT_STARTED = "not_started";
    public static final List<String> STATES = List.of(MERGED, RUNNING, STUCK, NOT_STARTED);

    private final DSLContext dsl;

    public MilestoneTracks(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * What a task's status counts as for its milestone: integrated is {@code merged}; running, verifying
     * and verified are {@code running}; needs_human and failed are {@code stuck}; pending and ready are
     * {@code not_started}; cancelled counts as nothing (null).
     */
    public static String state(TaskStatus status) {
        return switch (status) {
            case INTEGRATED -> MERGED;
            case RUNNING, VERIFYING, VERIFIED -> RUNNING;
            case NEEDS_HUMAN, FAILED -> STUCK;
            case PENDING, READY -> NOT_STARTED;
            case CANCELLED -> null;
        };
    }

    private record Row(UUID id, UUID projectId, String text, OffsetDateTime dueAt, OffsetDateTime createdAt,
                       String status, UUID supersedesId) {}

    /**
     * The active milestones of each project (its own, not a session's), the nearest date first and the
     * undated last, at most {@link BriefSnapshot#MAX_MILESTONES} each, with their tasks and label.
     *
     * @param at     now; {@code today} is its day in {@code zone}, where days are counted
     * @param margin {@link OnTrack}'s margin ({@code orange.brief.on-track-margin})
     * @return by project id; a project without an active milestone has no entry
     */
    public Map<UUID, List<Milestone>> read(Collection<UUID> projectIds, OffsetDateTime at, LocalDate today,
                                           ZoneId zone, double margin) {
        if (projectIds.isEmpty()) return Map.of();
        Map<UUID, Row> all = new HashMap<>();
        dsl.select(SPACE_ITEMS.ID, SPACE_ITEMS.PROJECT_ID, SPACE_ITEMS.BODY, SPACE_ITEMS.DUE_AT, SPACE_ITEMS.CREATED_AT,
                        SPACE_ITEMS.STATUS, SPACE_ITEMS.SUPERSEDES_ID)
                .from(SPACE_ITEMS)
                .where(SPACE_ITEMS.PROJECT_ID.in(projectIds)
                        .and(SPACE_ITEMS.SESSION_ID.isNull())
                        .and(SPACE_ITEMS.KIND.eq(SpaceItem.MILESTONE)))
                .forEach(r -> all.put(r.value1(), new Row(r.value1(), r.value2(),
                        one(bodyText(r.value3() == null ? null : r.value3().data()), BriefSnapshot.TEXT),
                        r.value4(), r.value5(), r.value6(), r.value7())));

        Map<UUID, List<Row>> active = new HashMap<>();
        all.values().stream().filter(r -> SpaceItem.ACTIVE.equals(r.status()))
                .forEach(r -> active.computeIfAbsent(r.projectId(), k -> new ArrayList<>()).add(r));
        Comparator<Row> byDate = Comparator
                .comparing(Row::dueAt, Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder()))
                .thenComparing(Row::createdAt)
                .thenComparing(Row::id);
        Map<UUID, List<UUID>> chains = new HashMap<>();
        Set<UUID> linked = new HashSet<>();
        active.values().forEach(rows -> {
            rows.sort(byDate);
            if (rows.size() > BriefSnapshot.MAX_MILESTONES) rows.subList(BriefSnapshot.MAX_MILESTONES, rows.size()).clear();
            for (Row m : rows) {
                List<UUID> chain = chain(m, all);
                chains.put(m.id(), chain);
                linked.addAll(chain);
            }
        });

        Map<UUID, Map<String, Integer>> tasksByItem = new HashMap<>();
        if (!linked.isEmpty()) {
            dsl.select(TASKS.MILESTONE_ID, TASKS.STATUS, DSL.count()).from(TASKS)
                    .where(TASKS.MILESTONE_ID.in(linked))
                    .groupBy(TASKS.MILESTONE_ID, TASKS.STATUS)
                    .forEach(r -> {
                        String state = state(TaskStatus.fromDb(r.value2()));
                        if (state != null) {
                            tasksByItem.computeIfAbsent(r.value1(), k -> new HashMap<>()).merge(state, r.value3(),
                                    Integer::sum);
                        }
                    });
        }

        Map<UUID, List<Milestone>> out = new LinkedHashMap<>();
        for (UUID projectId : projectIds) {
            List<Row> rows = active.get(projectId);
            if (rows == null) continue;
            List<Milestone> milestones = new ArrayList<>();
            for (Row m : rows) {
                Map<String, Integer> counted = new HashMap<>();
                for (UUID item : chains.get(m.id())) {
                    tasksByItem.getOrDefault(item, Map.of()).forEach((k, v) -> counted.merge(k, v, Integer::sum));
                }
                Map<String, Integer> tasks = new LinkedHashMap<>();
                for (String state : STATES) {
                    int n = counted.getOrDefault(state, 0);
                    if (n > 0) tasks.put(state, n);
                }
                // Editing a milestone supersedes it: its time runs from the first item of its chain.
                List<UUID> chain = chains.get(m.id());
                OffsetDateTime start = all.get(chain.getLast()).createdAt();
                milestones.add(milestone(projectId, m, start, tasks, at, today, zone, margin));
            }
            out.put(projectId, milestones);
        }
        return out;
    }

    /**
     * The milestone item that {@code milestoneId} is now: itself when active, else the active item at the
     * end of its supersede chain. Empty when the chain ends in a dropped or done milestone, or there is no
     * such item. A proposed edit (an agent's, not accepted) does not move the milestone.
     */
    public Optional<UUID> current(UUID milestoneId) {
        UUID at = milestoneId;
        Set<UUID> seen = new HashSet<>();
        while (at != null && seen.add(at)) {
            String status = dsl.select(SPACE_ITEMS.STATUS).from(SPACE_ITEMS).where(SPACE_ITEMS.ID.eq(at))
                    .fetchOne(SPACE_ITEMS.STATUS);
            if (status == null) return Optional.empty();
            if (SpaceItem.ACTIVE.equals(status)) return Optional.of(at);
            if (!SpaceItem.SUPERSEDED.equals(status)) return Optional.empty();
            // The item that replaced it. Only one item that supersedes it was ever accepted; others are
            // proposals, open or dropped. A dropped one is taken last: it is either such a proposal, or the
            // accepted one dropped since, and either way the chain ends there.
            at = dsl.select(SPACE_ITEMS.ID).from(SPACE_ITEMS)
                    .where(SPACE_ITEMS.SUPERSEDES_ID.eq(at).and(SPACE_ITEMS.STATUS.ne(SpaceItem.PROPOSED)))
                    .orderBy(DSL.case_(SPACE_ITEMS.STATUS).when(SpaceItem.ACTIVE, 0).when(SpaceItem.SUPERSEDED, 1)
                                    .when(SpaceItem.DONE, 2).else_(3).asc(),
                            SPACE_ITEMS.CREATED_AT.desc(), SPACE_ITEMS.ID.desc())
                    .limit(1)
                    .fetchOne(SPACE_ITEMS.ID);
        }
        return Optional.empty();
    }

    /** {@code m} and the items it superseded, newest first. */
    private static List<UUID> chain(Row m, Map<UUID, Row> all) {
        List<UUID> chain = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        Row at = m;
        while (at != null && seen.add(at.id())) {
            chain.add(at.id());
            at = at.supersedesId() == null ? null : all.get(at.supersedesId());
        }
        return chain;
    }

    /** @param start when the milestone was first set: the oldest item of its supersede chain */
    private Milestone milestone(UUID projectId, Row m, OffsetDateTime start, Map<String, Integer> tasks,
                                OffsetDateTime at, LocalDate today, ZoneId zone, double margin) {
        if (m.dueAt() == null) {
            return new Milestone(m.id().toString(), m.text(), null, null, null, false, null, tasks, null);
        }
        int daysLeft = (int) ChronoUnit.DAYS.between(today, m.dueAt().atZoneSameInstant(zone).toLocalDate());
        long whole = Duration.between(start, m.dueAt()).toMillis();
        long gone = Duration.between(start, at).toMillis();
        int elapsed = whole <= 0 ? 100 : (int) Math.clamp(100 * gone / whole, 0, 100);
        // Session work is not linked to a milestone (Phase D ruling 9): the count is the project's.
        int closedSince = dsl.fetchCount(dsl.selectOne().from(SESSION_WORK)
                .join(SESSIONS).on(SESSIONS.ID.eq(SESSION_WORK.SESSION_ID))
                .where(SESSIONS.PROJECT_ID.eq(projectId)
                        .and(SESSION_WORK.STATUS.eq(SessionWork.CLOSED))
                        .and(SESSION_WORK.CLOSED_AT.gt(start))));
        int total = tasks.values().stream().mapToInt(Integer::intValue).sum();
        String track = OnTrack.label(elapsed, tasks.getOrDefault(MERGED, 0), total, tasks.getOrDefault(STUCK, 0),
                margin);
        return new Milestone(m.id().toString(), m.text(), m.dueAt().toString(), daysLeft, elapsed, m.dueAt().isBefore(at),
                closedSince, tasks, track);
    }

    /** The text of a space item's body; empty when the body holds none. */
    private static String bodyText(String json) {
        if (json == null) return "";
        try {
            return Json.mapper().readTree(json).path("text").asText("");
        } catch (Exception e) {
            return "";
        }
    }
}
