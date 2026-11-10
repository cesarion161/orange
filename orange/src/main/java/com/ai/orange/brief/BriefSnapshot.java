package com.ai.orange.brief;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jooq.JSONB;

/**
 * The facts of a brief, as orange read them from its database at one moment
 * ({@link BriefSnapshots}). It is stored with the brief and everything a brief says in numbers
 * and dates comes from here, never from the model. Times and ids are ISO strings, and a missing
 * list is an empty one, so any stored snapshot reads back and renders.
 *
 * @param at               when the facts were read
 * @param since            the start of what the brief covers: the previous scheduled brief
 * @param zone             the user's zone, in which days are counted
 * @param projects         the active projects that get a line, most recently active first
 * @param otherProjects    names of active projects beyond {@link #MAX_PROJECTS}
 * @param proposedProjects how many projects await the user's confirmation
 * @param promptsTotal     the user's prompts since {@code since}, in any session
 * @param elsewhere        open attention items outside {@code projects}
 * @param tasks            orange's own tasks, which belong to no project yet
 */
public record BriefSnapshot(String at, String since, String zone, List<ProjectFacts> projects,
                            List<String> otherProjects, int proposedProjects, int pausedProjects, int promptsTotal,
                            List<Item> elsewhere, int elsewhereTotal, TaskFacts tasks) {

    public static final int MAX_PROJECTS = 12;
    public static final int MAX_GOALS = 5;
    public static final int MAX_MILESTONES = 5;
    public static final int MAX_SESSIONS = 8;
    public static final int MAX_CLOSED = 8;
    public static final int MAX_WAITING = 8;
    public static final int MAX_ELSEWHERE = 5;
    public static final int MAX_NEEDS_HUMAN = 5;
    /** Characters kept of a summary, a goal, a milestone or an intent. */
    public static final int TEXT = 200;
    /** Characters kept of a name, a branch or a title. */
    public static final int TITLE = 120;
    /** How far back a project's rhythm is read. */
    public static final int HISTORY_DAYS = 60;
    /** Sessions not heard from for longer than this are left out. */
    public static final int SESSION_DAYS = 7;

    /** A milestone this close, in days, is news by itself. */
    static final int MILESTONE_NEAR_DAYS = 2;

    public static final String CHECKING = "checking";
    public static final String CLOSED = "closed";
    public static final String ASKING = "asking";
    public static final String NEEDS_PERMISSION = "needs_permission";
    public static final String STOPPED = "stopped";
    public static final String WORKING = "working";
    public static final String SILENT = "silent";

    public BriefSnapshot {
        projects = list(projects);
        otherProjects = list(otherProjects);
        elsewhere = list(elsewhere);
        if (tasks == null) tasks = new TaskFacts(null, 0, null);
    }

    /**
     * What orange knows about one project.
     *
     * @param sessionsTotal the sessions heard from lately, before the cap on {@code sessions}
     * @param integrated    pieces of work closed since the last brief that were integrated
     * @param verified      and those closed as verified only
     * @param waitingTotal  open attention items, before the cap on {@code waiting}
     * @param waitingUrgent how many of them are urgent or critical
     * @param prompts       the user's prompts in this project since the last brief
     */
    public record ProjectFacts(String id, String name, String summary, List<String> goals,
                               List<Milestone> milestones, List<SessionFacts> sessions, int sessionsTotal,
                               List<Closed> closed, int integrated, int verified,
                               List<Item> waiting, int waitingTotal, int waitingUrgent,
                               int prompts, String lastPromptAt, Quiet quiet) {

        public ProjectFacts {
            goals = list(goals);
            milestones = list(milestones);
            sessions = list(sessions);
            closed = list(closed);
            waiting = list(waiting);
            if (quiet == null) quiet = new Quiet(Quiet.UNKNOWN, 0, null, null, null, null, null);
        }
    }

    /**
     * @param daysLeft       calendar days from today to the due day, negative when past; null without a date
     * @param elapsedPercent how much of the time from its creation to its due date has passed
     * @param closedSince    the project's work closed since the milestone was set; not linked to it
     */
    public record Milestone(String text, String dueAt, Integer daysLeft, Integer elapsedPercent, boolean overdue,
                            Integer closedSince) {}

    /** @param state see {@link #state} */
    public record SessionFacts(String state, String branch, String intent, String lastEventAt, String waitingSince) {}

    /** A piece of work that closed. @param result {@code integrated} or {@code verified} */
    public record Closed(String result, String intent, String closedAt) {}

    /** An open attention item. */
    public record Item(String kind, String urgency, String title, String createdAt) {}

    /**
     * @param byStatus        tasks that are not finished, counted by status
     * @param integratedSince tasks integrated since the last brief
     * @param needsHuman      titles of tasks waiting for a person, oldest first
     */
    public record TaskFacts(Map<String, Integer> byStatus, int integratedSince, List<String> needsHuman) {

        public TaskFacts {
            byStatus = byStatus == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(byStatus));
            needsHuman = list(needsHuman);
        }
    }

    public JSONB toJsonb() {
        return JSONB.valueOf(Json.mapper().valueToTree(this).toString());
    }

    /** Reads a stored snapshot. @throws IllegalArgumentException when it is not one */
    public static BriefSnapshot parse(JSONB json) {
        if (json == null || json.data() == null) throw new IllegalArgumentException("there is no snapshot");
        try {
            BriefSnapshot snapshot = Json.mapper().readValue(json.data(), BriefSnapshot.class);
            if (snapshot == null) throw new IllegalArgumentException("the snapshot is null");
            return snapshot;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("the snapshot cannot be read: " + e.getOriginalMessage(), e);
        }
    }

    /** The projects a brief writes about, in the snapshot's order. */
    public List<String> projectNames() {
        return projects.stream().map(ProjectFacts::name).toList();
    }

    /**
     * Whether a scheduled brief has anything to say that the one before ({@code previous}, null
     * when there was none) did not: something waits on the user, the user or orange did something
     * since, a milestone is close or overdue, or a project went quiet.
     */
    public boolean hasNews(BriefSnapshot previous) {
        if (previous == null) return true;
        if (elsewhereTotal > 0) return true;
        if (tasks.integratedSince() > 0 || !tasks.needsHuman().isEmpty()
                || tasks.byStatus().getOrDefault("needs_human", 0) > 0) {
            return true;
        }
        for (ProjectFacts p : projects) {
            if (p.waitingTotal() > 0 || p.prompts() > 0) return true;
            if (p.integrated() > 0 || p.verified() > 0 || !p.closed().isEmpty()) return true;
            for (Milestone m : p.milestones()) {
                if (m.overdue() || (m.daysLeft() != null && m.daysLeft() <= MILESTONE_NEAR_DAYS)) return true;
            }
            if (p.quiet().quiet() && !previous.wasQuiet(p)) return true;
        }
        return false;
    }

    /** Whether this snapshot already had the project as quiet. */
    private boolean wasQuiet(ProjectFacts now) {
        for (ProjectFacts p : projects) {
            boolean same = now.id() != null ? now.id().equals(p.id()) : Objects.equals(now.name(), p.name());
            if (same) return p.quiet().quiet();
        }
        return false;
    }

    /**
     * What a session is doing, in the brief's words: {@code checking} (orange is deciding its stop
     * or verifying its work), {@code closed}, {@code asking}, {@code needs_permission},
     * {@code stopped}, {@code working}, or {@code silent} (it says it works but nothing was heard
     * for longer than {@code staleAfter}). Any other status is returned as it is.
     */
    public static String state(String status, String waitingReason, Duration idle, Duration staleAfter) {
        if ("judging".equals(status) || "verifying".equals(status)) return CHECKING;
        if ("closed".equals(status)) return CLOSED;
        if ("question".equals(waitingReason)) return ASKING;
        if ("permission".equals(waitingReason)) return NEEDS_PERMISSION;
        if ("stopped".equals(status)) return STOPPED;
        if ("active".equals(status)) {
            return idle != null && staleAfter != null && idle.compareTo(staleAfter) > 0 ? SILENT : WORKING;
        }
        return status;
    }

    /** Whatever would end a line or cannot be seen: white space of any kind and control characters. */
    private static final Pattern BREAKS = Pattern.compile("(?U)[\\s\\p{Cntrl}]+");

    /** A text as one line of at most {@code max} characters, cut with a final {@code …}; null stays null. */
    static String one(String text, int max) {
        if (text == null) return null;
        String line = BREAKS.matcher(text).replaceAll(" ").strip();
        if (line.length() <= max) return line;
        int end = Math.max(0, max - 1);
        if (end > 0 && Character.isHighSurrogate(line.charAt(end - 1))) end--;
        return line.substring(0, end).stripTrailing() + "…";
    }

    private static <T> List<T> list(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }
}
