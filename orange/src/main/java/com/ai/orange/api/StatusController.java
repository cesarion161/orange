package com.ai.orange.api;

import com.ai.orange.concurrency.ConcurrencyGate;
import com.ai.orange.concurrency.ConcurrencyGate.RoleUsage;
import com.ai.orange.devenv.DevEnv;
import com.ai.orange.devenv.DevEnvRepository;
import com.ai.orange.devenv.DevEnvStatus;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEvent;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate "what's happening right now" endpoints. The chat orchestrator
 * polls these on a ~30s cadence to narrate progress without fanning out
 * to per-task tail calls.
 *
 * <p>Two endpoints today:
 * <ul>
 *   <li>{@code GET /events/live} — every {@code in_progress} task plus its
 *       last few events. Optional {@code role} filter.</li>
 *   <li>{@code GET /dev-envs} — env occupancy, ports, who holds each one.</li>
 * </ul>
 */
@RestController
@RequestMapping("/")
public class StatusController {

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final DevEnvRepository devEnvs;
    private final ConcurrencyGate concurrency;
    private final org.jooq.DSLContext dsl;

    public StatusController(TaskRepository tasks,
                            TaskEventRepository events,
                            DevEnvRepository devEnvs,
                            ConcurrencyGate concurrency,
                            org.jooq.DSLContext dsl) {
        this.tasks = tasks;
        this.events = events;
        this.devEnvs = devEnvs;
        this.concurrency = concurrency;
        this.dsl = dsl;
    }

    @GetMapping("/events/live")
    public ResponseEntity<List<LiveTaskResponse>> live(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String pipeline,
            @RequestParam(defaultValue = "3") int perTaskLimit) {
        List<Task> active = tasks.findInProgress(role).stream()
                .filter(t -> pipeline == null || pipeline.isBlank() || pipeline.equals(t.pipeline()))
                .toList();
        int safeLimit = Math.min(Math.max(perTaskLimit, 1), 50);
        List<LiveTaskResponse> rows = active.stream()
                .map(t -> {
                    List<TaskEvent> recent = events.findLatestByTask(t.id(), safeLimit);
                    return LiveTaskResponse.of(t, recent);
                })
                .toList();
        return ResponseEntity.ok(rows);
    }

    /**
     * One-shot aggregate health view: queue depths + env occupancy + per-role
     * concurrency usage. Used by the chat orchestrator skill to print a system
     * health one-liner without three separate calls.
     */
    @GetMapping("/health/aggregate")
    public ResponseEntity<Map<String, Object>> healthAggregate() {
        Map<String, Object> body = new LinkedHashMap<>();
        // Queue depths by status
        Map<String, Integer> queues = new LinkedHashMap<>();
        queues.put("ready", tasks.findRecent(TaskStatus.READY, 200).size());
        queues.put("in_progress", tasks.findInProgress(null).size());
        queues.put("pr_open", tasks.findRecent(TaskStatus.PR_OPEN, 200).size());
        queues.put("dev_ready", tasks.findRecent(TaskStatus.DEV_READY, 200).size());
        body.put("queues", queues);

        // Env occupancy
        List<DevEnv> envs = devEnvs.findAll();
        long leased = envs.stream().filter(e -> e.status() == DevEnvStatus.LEASED).count();
        body.put("envs", Map.of(
                "total", envs.size(),
                "leased", leased,
                "free", envs.size() - leased));

        // Per-role concurrency usage
        body.put("concurrency", concurrency.usage().values());

        // Aggregate spend from agent_runs.cost_usd
        java.math.BigDecimal allTime = (java.math.BigDecimal) dsl.fetchValue(
                "SELECT COALESCE(SUM(cost_usd), 0)::numeric FROM agent_runs");
        java.math.BigDecimal today = (java.math.BigDecimal) dsl.fetchValue(
                "SELECT COALESCE(SUM(cost_usd), 0)::numeric FROM agent_runs"
                        + " WHERE started_at >= now() - interval '24 hours'");
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("total_usd", allTime);
        cost.put("last_24h_usd", today);
        body.put("cost", cost);

        return ResponseEntity.ok(body);
    }

    @GetMapping("/dev-envs")
    public ResponseEntity<List<DevEnvResponse>> envs() {
        return ResponseEntity.ok(devEnvs.findAll().stream()
                .map(DevEnvResponse::of)
                .toList());
    }

    @GetMapping("/concurrency")
    public ResponseEntity<List<RoleUsage>> concurrency() {
        return ResponseEntity.ok(concurrency.usage().values().stream().toList());
    }
}
