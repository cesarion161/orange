package com.ai.orange.planner;

import com.ai.orange.agent.protocol.ProtocolCodec;
import com.ai.orange.task.DagValidator;
import com.ai.orange.task.EdgeKey;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.exception.DagCycleException;
import com.ai.orange.workflow.PipelineRegistry;
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
 * dispatcher calls {@link #ingest} to actually insert the graph and let the
 * downstream {@code task_ready} → {@code WorkflowStarter} machinery run it.
 * Pipeline-name resolution is a dispatch-time concern and lives here, not in
 * the planner — so a plan never has to know which pipelines are registered.
 */
@Service
public class PlanGraphService {

    static final String DEFAULT_PIPELINE = "dev_only";

    private final TaskService taskService;
    private final PipelineRegistry pipelines;
    private final DagValidator dagValidator;

    public PlanGraphService(TaskService taskService,
                            PipelineRegistry pipelines,
                            DagValidator dagValidator) {
        this.taskService = taskService;
        this.pipelines = pipelines;
        this.dagValidator = dagValidator;
    }

    /**
     * Structural validation the planner can run without knowing anything about
     * pipelines: keys are unique, edges reference real nodes, and the graph is
     * acyclic. No DB access. Throws {@link IllegalArgumentException} (or
     * {@code DagCycleException}) on a malformed plan.
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
     * Validate (incl. pipeline resolution) and insert the graph in one
     * transaction via {@link TaskService#createGraph}. Root tasks are flipped
     * to {@code ready}, which fires {@code pg_notify('task_ready')} and lets the
     * orchestrator start them. Returns the created tasks.
     */
    public List<Task> ingest(PlanJson plan, String defaultPipeline) {
        if (plan == null || plan.tasks() == null || plan.tasks().isEmpty()) {
            throw new IllegalArgumentException("plan has no tasks");
        }
        List<TaskDef> defs = toDefs(plan.tasks(), defaultPipeline);
        List<EdgeKey> edges = toEdges(plan.edges());
        return taskService.createGraph(defs, edges);
    }

    // ─────────────────────────── helpers ────────────────────────────────

    /**
     * Convert each {@link PlanJson.PlanTask} into a {@link TaskDef}. Resolves
     * each task's pipeline (task value → caller default → service default),
     * validates against {@link PipelineRegistry}, and stamps {@code overrides}
     * onto {@code metadata} so the executor side can read them later.
     *
     * @throws IllegalArgumentException if any task references an unknown pipeline.
     */
    private List<TaskDef> toDefs(List<PlanJson.PlanTask> tasks, String callerDefault) {
        String fallback = (callerDefault == null || callerDefault.isBlank())
                ? DEFAULT_PIPELINE : callerDefault;
        if (!pipelines.contains(fallback)) {
            throw new IllegalArgumentException(
                    "default pipeline '" + fallback + "' is not registered");
        }
        List<TaskDef> defs = new ArrayList<>(tasks.size());
        for (PlanJson.PlanTask t : tasks) {
            String pipeline = (t.pipeline() == null || t.pipeline().isBlank())
                    ? fallback : t.pipeline();
            if (!pipelines.contains(pipeline)) {
                throw new IllegalArgumentException(
                        "task '" + t.key() + "' references unknown pipeline '" + pipeline + "'");
            }
            JSONB metadata = overridesToMetadata(t.overrides());
            defs.add(new TaskDef(
                    t.key(),
                    t.title(),
                    t.description(),
                    (t.role() == null || t.role().isBlank()) ? "dev" : t.role(),
                    pipeline,
                    t.priority(),
                    metadata));
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
            String json = ProtocolCodec.mapper().writeValueAsString(Map.of("overrides", overrides));
            return JSONB.valueOf(json);
        } catch (Exception e) {
            // Overrides are advisory — never block plan ingestion over a serialization quirk.
            return null;
        }
    }
}
