package com.ai.orange.api;

import com.ai.orange.evidence.EvidenceRepository;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.TaskCancellation;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEvent;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskService service;
    private final TaskRepository taskRepo;
    private final TaskEventRepository eventRepo;
    private final TaskCancellation cancellation;
    private final RunRepository runs;
    private final EvidenceRepository evidence;

    public TaskController(TaskService service,
                          TaskRepository taskRepo,
                          TaskEventRepository eventRepo,
                          TaskCancellation cancellation,
                          RunRepository runs,
                          EvidenceRepository evidence) {
        this.service = service;
        this.taskRepo = taskRepo;
        this.eventRepo = eventRepo;
        this.cancellation = cancellation;
        this.runs = runs;
        this.evidence = evidence;
    }

    /** The task's runs, newest first. */
    @GetMapping("/{id}/runs")
    public ResponseEntity<List<RunResponse>> runs(@PathVariable UUID id) {
        if (service.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        List<RunResponse> rows = new java.util.ArrayList<>(runs.findByTask(id).stream().map(RunResponse::of).toList());
        java.util.Collections.reverse(rows);
        return ResponseEntity.ok(rows);
    }

    /** Everything orange checked on the task's heads, newest first. */
    @GetMapping("/{id}/evidence")
    public ResponseEntity<List<EvidenceResponse>> evidence(@PathVariable UUID id) {
        if (service.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(evidence.findByTask(id).stream().map(EvidenceResponse::of).toList());
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskCreateRequest req) {
        TaskDef def = new TaskDef(
                "manual-" + UUID.randomUUID(),
                req.title(),
                req.description(),
                req.role(), // TaskDef normalises it to implementer or chore
                req.priority(),
                null);
        List<Task> created = service.createGraph(List.of(def), List.of());
        Task t = created.get(0);
        return ResponseEntity.status(201).body(TaskResponse.of(t));
    }

    @GetMapping
    public ResponseEntity<List<TaskResponse>> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {
        TaskStatus filter = null;
        if (status != null && !status.isBlank()) {
            try {
                filter = TaskStatus.fromDb(status);
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().build();
            }
        }
        List<TaskResponse> response = taskRepo.findRecent(filter, limit).stream()
                .map(TaskResponse::of)
                .toList();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TaskResponse> get(@PathVariable UUID id) {
        return service.findById(id)
                .map(t -> ResponseEntity.ok(TaskResponse.of(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{id}/events")
    public ResponseEntity<List<TaskEventResponse>> events(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "0") long since,
            @RequestParam(defaultValue = "100") int limit) {
        if (service.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        List<TaskEvent> rows = eventRepo.findByTaskSince(id, since, limit);
        return ResponseEntity.ok(rows.stream().map(TaskEventResponse::of).toList());
    }

    /**
     * Reopen a FAILED or TEST_DONE task by transitioning it back to READY with
     * a reason attached as augmentation metadata. Operators call this directly
     * or through {@code orange_reopen_task}.
     */
    @PostMapping("/{id}/reopen")
    public ResponseEntity<Map<String, Object>> reopen(@PathVariable UUID id,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        if (service.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        String reason = body == null ? null : (String) body.get("reason");
        String actor = body == null ? null : (String) body.get("actor");
        boolean ok = service.reopenTask(id, reason, actor);
        if (!ok) {
            Task t = service.findById(id).orElseThrow();
            return ResponseEntity.status(409).body(Map.of(
                    "ok", false,
                    "status", t.status().dbValue(),
                    "reason", "task is not in a reopenable state"));
        }
        Task reopened = service.findById(id).orElseThrow();
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "id", reopened.id(),
                "status", reopened.status().dbValue()));
    }

    /**
     * Replay a task by creating a fresh copy on the ready queue. The original
     * row is left untouched; the new row carries a {@code metadata.replayed_from}
     * pointer for audit. Used during testing/flakiness investigations.
     */
    @PostMapping("/{id}/replay")
    public ResponseEntity<TaskResponse> replay(@PathVariable UUID id,
                                                @RequestBody(required = false) Map<String, Object> body) {
        if (service.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        String actor = body == null ? null : (String) body.get("actor");
        Task replayed = service.replayTask(id, actor);
        return ResponseEntity.status(201).body(TaskResponse.of(replayed));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<TaskResponse> cancel(@PathVariable UUID id) {
        // Cancelled first (under the task's lock, with its queued runs), then a running agent is killed.
        Task before = cancellation.cancel(id).orElse(null);
        if (before == null) return ResponseEntity.notFound().build();
        if (before.status().isTerminal()) {
            return ResponseEntity.status(409).body(TaskResponse.of(before));
        }
        return ResponseEntity.ok(TaskResponse.of(service.findById(id).orElseThrow()));
    }
}
