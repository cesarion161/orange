package com.ai.orange.planner;

import com.ai.orange.project.ProjectService;
import com.ai.orange.project.TaskPlacement;

import com.ai.orange.json.Json;
import com.ai.orange.task.DagValidator;
import com.ai.orange.task.EdgeKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.exception.DagCycleException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.JSONB;
import org.springframework.stereotype.Service;

/**
 * The single place that turns a {@link PlanJson} DAG into an orange task graph.
 *
 * <p>This is the seam between planner and dispatcher: the planner only ever
 * asks {@link #validateAcyclic} (is this a well-formed DAG?), while the
 * dispatcher calls {@link #ingest} to actually insert the graph.
 */
@Service
public class PlanGraphService {

    private final TaskService taskService;
    private final DagValidator dagValidator;

    private final ProjectService projects;

    public PlanGraphService(TaskService taskService, DagValidator dagValidator, ProjectService projects) {
        this.projects = projects;
        this.taskService = taskService;
        this.dagValidator = dagValidator;
    }

    /**
     * Structural validation: keys are unique, edges reference real nodes, and the graph is
     * acyclic. No DB access. Throws {@link IllegalArgumentException} on any malformed plan — a
     * detected cycle is normalised from {@code DagCycleException} so callers handle a single type.
     */
    public void validateAcyclic(PlanJson plan) {
        if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
            return; // emptiness is the caller's concern (distinct status)
        }
        Set<String> keys = new LinkedHashSet<>();
        for (PlanJson.PlanTask t : plan.tasks()) {
            if (!keys.add(t.key())) {
                throw new IllegalArgumentException("duplicate task key: " + t.key());
            }
        }
        try {
            dagValidator.validate(keys, toEdges(plan.edges()));
        } catch (DagCycleException e) {
            // Normalise to IllegalArgumentException so every planner path maps to
            // invalid_plan / 422 uniformly (the dispatcher handles the raw type).
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * Validate and insert the graph in one transaction via {@link TaskService#createGraph}.
     * Root tasks are flipped to {@code ready}. Returns the created tasks.
     */
    public List<Task> ingest(PlanJson plan) {
        return ingest(plan, null, null);
    }

    /**
     * Like {@link #ingest(PlanJson)}, in {@code project} (and {@code repo}) when given, else in the
     * plan's own project and repo. A task may name another repo of the same project.
     *
     * @throws IllegalArgumentException when the project or a repo cannot be placed; nothing is inserted
     */
    public List<Task> ingest(PlanJson plan, String project, String repo) {
        return ingest(plan, project, repo, null, null);
    }

    /**
     * Like {@link #ingest(PlanJson, String, String)}, for the plan {@code planId} (a {@code plans} row),
     * whose tasks serve {@code milestoneId}; either may be null.
     */
    public List<Task> ingest(PlanJson plan, String project, String repo, UUID planId, UUID milestoneId) {
        if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
            throw new IllegalArgumentException("plan has no tasks");
        }
        boolean named = project != null && !project.isBlank();
        String projectRef = named ? project : plan.project();
        String repoRef = named ? repo : plan.repo();
        TaskPlacement placement = projects.placeTask(projectRef, repoRef);
        List<TaskDef> defs = new ArrayList<>(plan.tasks().size());
        for (PlanJson.PlanTask t : plan.tasks()) {
            TaskPlacement own = t.repo() == null || t.repo().isBlank() ? placement : projects.placeTask(projectRef, t.repo());
            defs.add(toDef(t, own, planId, milestoneId));
        }
        return taskService.createGraph(defs, toEdges(plan.edges()));
    }

    // ─────────────────────────── helpers ────────────────────────────────

    /** A plan task as a {@link TaskDef}, with its metadata and where it is done. */
    private static TaskDef toDef(PlanJson.PlanTask t, TaskPlacement placement, UUID planId, UUID milestoneId) {
        return new TaskDef(
                t.key(),
                t.title(),
                t.description(),
                t.role(), // TaskDef normalises it to implementer or chore
                t.priority(),
                metadata(t),
                placement,
                planId,
                milestoneId);
    }

    private static List<EdgeKey> toEdges(List<PlanJson.PlanEdge> raw) {
        return raw == null ? List.of()
                : raw.stream().map(e -> new EdgeKey(e.from(), e.to())).toList();
    }

    /**
     * The task's metadata: its {@code overrides} (advisory), and its {@code risk} and {@code review}
     * flags at the top level, where routing and the review policy read them. Null when it has none.
     */
    private static JSONB metadata(PlanJson.PlanTask t) {
        Map<String, Object> meta = new java.util.LinkedHashMap<>();
        if (t.overrides() != null && !t.overrides().isEmpty()) meta.put("overrides", t.overrides());
        if (t.risk() != null) meta.put("risk", t.risk());
        if (t.review() != null) meta.put("review", t.review());
        if (meta.isEmpty()) return null;
        try {
            return JSONB.valueOf(Json.mapper().writeValueAsString(meta));
        } catch (Exception e) {
            // Overrides are advisory — never block plan ingestion over a serialization quirk.
            return null;
        }
    }
}
