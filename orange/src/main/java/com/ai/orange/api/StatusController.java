package com.ai.orange.api;

import com.ai.orange.devenv.DevEnv;
import com.ai.orange.devenv.DevEnvRepository;
import com.ai.orange.devenv.DevEnvStatus;
import com.ai.orange.run.Run;
import com.ai.orange.run.RunEventRepository;
import com.ai.orange.run.RunRepository;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Aggregate "what's happening right now" endpoints, polled by the plugin to narrate progress
 * without fanning out to per-task calls.
 *
 * <ul>
 *   <li>{@code GET /events/live}: every running run with its latest events; optional {@code role}.</li>
 *   <li>{@code GET /health/aggregate}: task queues, runs per kind, the pause, spend, envs.</li>
 *   <li>{@code GET /dev-envs}: env occupancy, ports, who holds each one.</li>
 * </ul>
 */
@RestController
@RequestMapping("/")
public class StatusController {

    private final TaskRepository tasks;
    private final RunRepository runs;
    private final RunEventRepository runEvents;
    private final DevEnvRepository devEnvs;
    private final SchedulerView scheduler;
    private final DSLContext dsl;

    public StatusController(TaskRepository tasks, RunRepository runs, RunEventRepository runEvents,
                            DevEnvRepository devEnvs, SchedulerView scheduler, DSLContext dsl) {
        this.tasks = tasks;
        this.runs = runs;
        this.runEvents = runEvents;
        this.devEnvs = devEnvs;
        this.scheduler = scheduler;
        this.dsl = dsl;
    }

    /** One row per running run: its task, kind, model, and its last {@code limit} events (newest first). */
    @GetMapping("/events/live")
    public ResponseEntity<List<LiveRunResponse>> live(@RequestParam(required = false) String role,
                                                      @RequestParam(defaultValue = "3") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 50);
        List<LiveRunResponse> rows = runs.findRunning().stream()
                .filter(r -> role == null || role.isBlank() || role.equals(r.role()))
                .map(r -> LiveRunResponse.of(r, title(r), runEvents.findLatestByRun(r.id(), safeLimit)))
                .toList();
        return ResponseEntity.ok(rows);
    }

    @GetMapping("/health/aggregate")
    public ResponseEntity<Map<String, Object>> healthAggregate() {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Integer> queues = new LinkedHashMap<>();
        for (TaskStatus s : List.of(TaskStatus.READY, TaskStatus.RUNNING, TaskStatus.VERIFYING,
                TaskStatus.VERIFIED, TaskStatus.NEEDS_HUMAN)) {
            queues.put(s.dbValue(), tasks.countByStatus(s));
        }
        body.put("queues", queues);
        body.put("runs", scheduler.runs());
        body.put("paused", scheduler.paused());

        // Spend across every run, plan runs included.
        BigDecimal allTime = (BigDecimal) dsl.fetchValue("SELECT COALESCE(SUM(cost_usd), 0)::numeric FROM runs");
        BigDecimal today = (BigDecimal) dsl.fetchValue("SELECT COALESCE(SUM(cost_usd), 0)::numeric FROM runs"
                + " WHERE started_at >= now() - interval '24 hours'");
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("total_usd", allTime);
        cost.put("last_24h_usd", today);
        body.put("cost", cost);

        List<DevEnv> envs = devEnvs.findAll();
        long leased = envs.stream().filter(e -> e.status() == DevEnvStatus.LEASED).count();
        body.put("envs", Map.of("total", envs.size(), "leased", leased, "free", envs.size() - leased));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/dev-envs")
    public ResponseEntity<List<DevEnvResponse>> envs() {
        return ResponseEntity.ok(devEnvs.findAll().stream().map(DevEnvResponse::of).toList());
    }

    private String title(Run r) {
        return r.taskId() == null ? null : tasks.findById(r.taskId()).map(Task::title).orElse(null);
    }
}
