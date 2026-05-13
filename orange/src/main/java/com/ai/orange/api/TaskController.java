package com.ai.orange.api;

import com.ai.orange.task.ClaimService;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskClaimRepository;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskEvent;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowStub;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final TaskService service;
    private final TaskRepository taskRepo;
    private final TaskEventRepository eventRepo;
    private final ClaimService claimService;
    private final TaskClaimRepository claimRepo;
    private final WorkflowClient workflowClient;

    public TaskController(TaskService service,
                          TaskRepository taskRepo,
                          TaskEventRepository eventRepo,
                          ClaimService claimService,
                          TaskClaimRepository claimRepo,
                          WorkflowClient workflowClient) {
        this.service = service;
        this.taskRepo = taskRepo;
        this.eventRepo = eventRepo;
        this.claimService = claimService;
        this.claimRepo = claimRepo;
        this.workflowClient = workflowClient;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskCreateRequest req) {
        TaskDef def = new TaskDef(
                "manual-" + UUID.randomUUID(),
                req.title(),
                req.description(),
                req.role() == null ? "dev" : req.role(),
                req.pipeline(),
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
     * Read the parent tasks' captured artifacts. The chat-as-executor MCP
     * surface calls this so a worker can peek at upstream results before
     * deciding how to attack its task.
     */
    @GetMapping("/{id}/dep-artifacts")
    public ResponseEntity<Map<UUID, Map<String, Object>>> depArtifacts(@PathVariable UUID id) {
        if (service.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(claimService.dependencyArtifacts(id));
    }

    /**
     * Reopen a FAILED or TEST_DONE task by transitioning it back to READY with
     * a reason attached as augmentation metadata. The triage agent calls this
     * via {@code orange_reopen_task}; operators can also hit it directly.
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
        Task task = service.findById(id).orElse(null);
        if (task == null) return ResponseEntity.notFound().build();
        if (task.status().isTerminal()) {
            return ResponseEntity.status(409).body(TaskResponse.of(task));
        }

        // Best-effort terminate of the Temporal workflow. If the workflow
        // isn't running (or never existed), we still flip the task to
        // CANCELLED below.
        try {
            WorkflowStub stub = workflowClient.newUntypedWorkflowStub("task-" + id);
            stub.terminate("user-cancellation");
        } catch (WorkflowNotFoundException nf) {
            log.info("no workflow to terminate for task {}", id);
        } catch (Exception e) {
            log.warn("workflow termination for task {} threw: {}", id, e.getMessage());
        }

        // Chat-driven workers don't have a workflow to terminate — they poll
        // the claim's cancel_requested flag on heartbeat. Flip it so they
        // notice promptly and call release.
        claimRepo.requestCancel(id);

        taskRepo.transitionStatus(id, task.status(), TaskStatus.CANCELLED);
        Task updated = service.findById(id).orElseThrow();
        return ResponseEntity.ok(TaskResponse.of(updated));
    }
}
