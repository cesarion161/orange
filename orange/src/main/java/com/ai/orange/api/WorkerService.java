package com.ai.orange.api;

import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEvent;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Computes the live "workers" view — each {@code in_progress} task represents
 * one running agent instance. Derives a coarse activity bucket per worker from
 * the task's role + recent events so the overview can render
 * "4 devs working, 2 testers waiting for env, 1 tester testing" without
 * making the chat / dashboard infer state from raw event streams.
 *
 * <p>This view is computed on every request — it scans only in_progress tasks
 * (typically a small set) plus a small batch of recent events per task. Fast
 * enough for a 5s dashboard poll.
 */
@Service
public class WorkerService {

    private final TaskRepository tasks;
    private final TaskEventRepository events;

    public WorkerService(TaskRepository tasks, TaskEventRepository events) {
        this.tasks = tasks;
        this.events = events;
    }

    public List<WorkerInstance> snapshot() {
        List<Task> active = tasks.findInProgress(null);
        List<WorkerInstance> out = new ArrayList<>(active.size());
        for (Task t : active) {
            List<TaskEvent> recent = events.findLatestByTask(t.id(), 30);
            out.add(WorkerInstance.from(t, recent));
        }
        return out;
    }

    /**
     * Buckets the snapshot by {@code (role, activity)}. Returned map preserves
     * insertion order: roles in their seeded sequence, activities in the order
     * defined by {@link Activity#values()}.
     */
    public Map<String, Map<Activity, Integer>> aggregate(List<WorkerInstance> snapshot) {
        Map<String, Map<Activity, Integer>> grouped = new LinkedHashMap<>();
        // Seed with known roles so the dashboard renders empty rows for roles
        // with no in-flight work — easier to read than missing rows.
        for (String role : List.of("dev", "tester", "reviewer", "planner", "triage")) {
            grouped.put(role, emptyRoleBuckets());
        }
        for (WorkerInstance w : snapshot) {
            grouped.computeIfAbsent(w.role(), r -> emptyRoleBuckets());
            Map<Activity, Integer> buckets = grouped.get(w.role());
            buckets.merge(w.activity(), 1, Integer::sum);
        }
        return grouped;
    }

    private static Map<Activity, Integer> emptyRoleBuckets() {
        Map<Activity, Integer> b = new LinkedHashMap<>();
        for (Activity a : Activity.values()) b.put(a, 0);
        return b;
    }

    // ─────────────────────────── types ──────────────────────────────────

    public record WorkerInstance(
            UUID taskId,
            String title,
            String role,
            String pipeline,
            String claimedBy,
            OffsetDateTime claimedAt,
            Activity activity,
            String latestEventType) {

        static WorkerInstance from(Task t, List<TaskEvent> recent) {
            String latest = recent.isEmpty() ? null : recent.get(0).eventType();
            Set<String> recentTypes = new HashSet<>();
            for (TaskEvent e : recent) recentTypes.add(e.eventType());
            return new WorkerInstance(
                    t.id(),
                    t.title(),
                    t.role(),
                    t.pipeline(),
                    t.claimedBy(),
                    t.claimedAt(),
                    deriveActivity(t.role(), latest, recentTypes),
                    latest);
        }

        private static Activity deriveActivity(String role, String latestType, Set<String> recent) {
            // Tester role gets a finer-grained breakdown around env leasing.
            if ("tester".equals(role)) {
                if (recent.contains("env_compose_failed")) return Activity.ERRORED;
                if (recent.contains("env_acquired")) return Activity.TESTING;
                return Activity.WAITING_FOR_ENV;
            }
            if (latestType == null) return Activity.STARTING;
            if ("claim_failed".equals(latestType) || "build_broken".equals(latestType)
                    || "env_compose_failed".equals(latestType)) {
                return Activity.ERRORED;
            }
            if ("final".equals(latestType) || "claim_complete".equals(latestType)) {
                return Activity.COMPLETING;
            }
            return Activity.WORKING;
        }
    }

    public enum Activity {
        STARTING, WORKING, WAITING_FOR_ENV, TESTING, COMPLETING, ERRORED;

        public String label() {
            return switch (this) {
                case STARTING -> "starting";
                case WORKING -> "working";
                case WAITING_FOR_ENV -> "waiting for env";
                case TESTING -> "testing";
                case COMPLETING -> "completing";
                case ERRORED -> "errored";
            };
        }
    }
}
