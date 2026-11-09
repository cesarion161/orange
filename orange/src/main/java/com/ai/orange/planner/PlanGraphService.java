package com.ai.orange.planner;

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

    public PlanGraphService(TaskService taskService, DagValidator dagValidator) {
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
        if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
            throw new IllegalArgumentException("plan has no tasks");
        }
        return taskService.createGraph(toDefs(plan.tasks()), toEdges(plan.edges()));
    }

    // ─────────────────────────── helpers ────────────────────────────────

    /** Converts each plan task into a {@link TaskDef}, stamping {@code overrides} onto metadata. */
    private static List<TaskDef> toDefs(List<PlanJson.PlanTask> tasks) {
        List<TaskDef> defs = new ArrayList<>(tasks.size());
        for (PlanJson.PlanTask t : tasks) {
            defs.add(new TaskDef(
                    t.key(),
                    t.title(),
                    t.description(),
                    t.role(), // TaskDef normalises it to implementer or chore
                    t.priority(),
                    overridesToMetadata(t.overrides())));
        }
        return defs;
    }

    private static List<EdgeKey> toEdges(List<PlanJson.PlanEdge> raw) {
        return raw == null ? List.of()
                : raw.stream().map(e -> new EdgeKey(e.from(), e.to())).toList();
    }

    private static JSONB overridesToMetadata(Map<String, Object> overrides) {
        if (overrides == null || overrides.isEmpty()) return null;
        try {
            String json = Json.mapper().writeValueAsString(Map.of("overrides", overrides));
            return JSONB.valueOf(json);
        } catch (Exception e) {
            // Overrides are advisory — never block plan ingestion over a serialization quirk.
            return null;
        }
    }
}
