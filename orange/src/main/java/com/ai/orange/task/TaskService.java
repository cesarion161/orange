package com.ai.orange.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {

    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final TaskRepository tasks;
    private final TaskEdgeRepository edges;
    private final TaskEventRepository events;
    private final DagValidator dagValidator;
    private final ObjectMapper json = new ObjectMapper();

    public TaskService(TaskRepository tasks,
                       TaskEdgeRepository edges,
                       TaskEventRepository events,
                       DagValidator dagValidator) {
        this.tasks = tasks;
        this.edges = edges;
        this.events = events;
        this.dagValidator = dagValidator;
    }

    /**
     * Inserts a graph of tasks + edges in a single transaction. Validates the
     * DAG is acyclic first; rejects with {@link com.ai.orange.task.exception.DagCycleException}
     * before touching the DB if not. Tasks with no incoming edges in the
     * proposed graph are flipped from {@code pending} to {@code ready} so an
     * agent can claim them immediately.
     *
     * @return the created tasks, in the same order as {@code defs}.
     */
    @Transactional
    public List<Task> createGraph(List<TaskDef> defs, Collection<EdgeKey> proposedEdges) {
        validateInput(defs, proposedEdges);

        Set<String> keys = new HashSet<>();
        for (TaskDef d : defs) {
            if (!keys.add(d.key())) {
                throw new IllegalArgumentException("duplicate task key: " + d.key());
            }
        }
        dagValidator.validate(keys, proposedEdges);

        Map<String, UUID> keyToId = new HashMap<>(defs.size());
        List<Task> created = new ArrayList<>(defs.size());
        for (TaskDef def : defs) {
            Task t = tasks.insert(def);
            keyToId.put(def.key(), t.id());
            created.add(t);
        }

        for (EdgeKey e : proposedEdges) {
            edges.insert(keyToId.get(e.from()), keyToId.get(e.to()));
        }

        Set<String> hasIncoming = new HashSet<>();
        for (EdgeKey e : proposedEdges) hasIncoming.add(e.to());
        for (TaskDef def : defs) {
            if (!hasIncoming.contains(def.key())) {
                tasks.markReadyIfPending(keyToId.get(def.key()));
            }
        }

        // Re-fetch so callers see the post-transition status. Cheap on insert volumes.
        List<Task> refreshed = new ArrayList<>(created.size());
        for (Task t : created) {
            refreshed.add(tasks.findById(t.id()).orElseThrow());
        }
        return refreshed;
    }

    public Optional<Task> findById(UUID id) {
        return tasks.findById(id);
    }

    /**
     * Operator retry of a FAILED or NEEDS_HUMAN task: back to READY with its failure count
     * reset (so the next run starts on the base tier again), keeping its branch and PR, and a
     * {@code reopen_reason} block in metadata for the next attempt's prompt. CANCELLED stays
     * terminal — that was an explicit user decision.
     *
     * <p>Called through {@code POST /tasks/{id}/reopen}.
     *
     * @return {@code true} if the task was actually transitioned. {@code false}
     *     if the task was in a non-reopenable state.
     */
    @Transactional
    public boolean reopenTask(UUID taskId, String reason, String actor) {
        Task t = tasks.findById(taskId).orElse(null);
        if (t == null) return false;
        if (t.status() != TaskStatus.FAILED && t.status() != TaskStatus.NEEDS_HUMAN) {
            log.info("reopenTask refused for {}: status={} not reopenable", taskId, t.status());
            return false;
        }
        boolean ok = tasks.reopen(taskId, t.status());
        if (!ok) return false;

        Map<String, Object> meta = parseMetadata(t.metadata());
        Map<String, Object> reopen = new HashMap<>();
        reopen.put("reason", reason == null ? "" : reason);
        reopen.put("reopened_at", java.time.OffsetDateTime.now().toString());
        reopen.put("reopened_by", actor == null ? "system" : actor);
        reopen.put("prior_status", t.status().dbValue());
        meta.put("reopen_reason", reopen);
        tasks.updateMetadata(taskId, asJsonb(meta));

        events.append(taskId, actor == null ? "system" : actor, "task_reopened",
                asJsonb(Map.of(
                        "reason", reason == null ? "" : reason,
                        "prior_status", t.status().dbValue())));
        return true;
    }

    /**
     * Copy an existing task as a fresh ready task. Same title/description/role;
     * metadata gets a {@code replayed_from} pointer back to the
     * original so the agent can see the lineage and the operator can audit it.
     * No dependencies are copied — replay is a clean re-attempt, not a graph
     * branch.
     */
    @Transactional
    public Task replayTask(UUID sourceTaskId, String actor) {
        Task src = tasks.findById(sourceTaskId)
                .orElseThrow(() -> new IllegalArgumentException("task " + sourceTaskId + " not found"));
        Map<String, Object> meta = new HashMap<>();
        meta.put("replayed_from", sourceTaskId.toString());
        meta.put("replayed_at", java.time.OffsetDateTime.now().toString());
        meta.put("replayed_by", actor == null ? "system" : actor);
        TaskDef def = new TaskDef(
                "replay-" + UUID.randomUUID(),
                src.title(),
                src.description(),
                src.role(),
                src.priority(),
                asJsonb(meta));
        Task created = createGraph(List.of(def), List.of()).get(0);
        events.append(created.id(), actor == null ? "system" : actor, "task_replayed",
                asJsonb(Map.of("source_task_id", sourceTaskId.toString())));
        return created;
    }

    private Map<String, Object> parseMetadata(JSONB meta) {
        if (meta == null) return new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(meta.data(), Map.class);
            return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private JSONB asJsonb(Map<String, Object> map) {
        try {
            return JSONB.valueOf(json.writeValueAsString(map));
        } catch (JsonProcessingException e) {
            return JSONB.valueOf("{}");
        }
    }

    private static void validateInput(List<TaskDef> defs, Collection<EdgeKey> edges) {
        if (defs == null || defs.isEmpty()) {
            throw new IllegalArgumentException("at least one task required");
        }
        if (edges == null) {
            throw new IllegalArgumentException("edges must not be null (pass empty list for a flat graph)");
        }
    }
}
