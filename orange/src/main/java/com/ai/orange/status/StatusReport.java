package com.ai.orange.status;

import com.ai.orange.brief.BriefSnapshot.Milestone;
import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything in flight, by project (remote-first spec §7.5): what {@code orange_status} shows and the
 * steering tab's start context is cut from. Read by {@link StatusService}, rendered by {@link StatusText}.
 *
 * @param at              when it was read, ISO with the user's offset
 * @param pause           the scheduler's pause; null when it runs
 * @param projects        the active projects, those that want the user first
 * @param openAttention   attention items not resolved yet, of any project or none
 * @param urgentAttention of those, the urgent and critical ones
 * @param runningRuns     runs running now, of every kind
 * @param unplaced        unfinished tasks of no project (the legacy base repo), stuck first, at most
 *                        {@link StatusService#MAX_TASKS}
 */
public record StatusReport(String at, Pause pause, List<ProjectStatus> projects, int openAttention,
                           int urgentAttention, int runningRuns, List<TaskLine> unplaced) {

    private static final ObjectMapper SNAKE = Json.mapper().copy()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    public StatusReport {
        projects = projects == null ? List.of() : List.copyOf(projects);
        unplaced = unplaced == null ? List.of() : List.copyOf(unplaced);
    }

    /** @param until null: until someone resumes */
    public record Pause(String reason, String until, String since) {}

    /**
     * @param plans        its plans in flight: {@code planning} and {@code asking}, oldest first
     * @param tasksByState its unfinished tasks counted by status, stuck first
     * @param tasks        its unfinished tasks, stuck first, at most {@link StatusService#MAX_TASKS}
     * @param integratedToday tasks integrated since the start of the user's day
     * @param milestones   from {@link com.ai.orange.brief.MilestoneTracks}
     * @param openAttention its attention items not resolved yet
     */
    public record ProjectStatus(String id, String name, List<PlanLine> plans, Map<String, Integer> tasksByState,
                                List<TaskLine> tasks, int integratedToday, List<Milestone> milestones,
                                int openAttention) {

        public ProjectStatus {
            plans = plans == null ? List.of() : List.copyOf(plans);
            tasksByState = tasksByState == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(tasksByState));
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
            milestones = milestones == null ? List.of() : List.copyOf(milestones);
        }
    }

    /** @param reason why it asks; null while planning */
    public record PlanLine(String id, String status, String request, String reason, String since) {}

    /**
     * @param id8       the task's short id (its last 8 hex digits), which {@code orange_task} takes back
     * @param milestone the text of the milestone it serves, when it serves a current one
     */
    public record TaskLine(String id8, String title, String status, String milestone) {}

    /** As JSON with snake_case names, like the other steering results. */
    public com.fasterxml.jackson.databind.JsonNode toJson() {
        return SNAKE.valueToTree(this);
    }
}
