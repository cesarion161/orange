package com.ai.orange.api;

import com.ai.orange.planner.PlannerService;
import com.ai.orange.planner.PlannerService.PlanResult;
import org.springframework.web.bind.annotation.ExceptionHandler;
import com.ai.orange.task.ClaimService;
import com.ai.orange.task.ClaimService.ClaimedTask;
import com.ai.orange.task.ClaimService.CompleteResult;
import com.ai.orange.task.ClaimService.FailResult;
import com.ai.orange.task.ClaimService.HeartbeatResult;
import com.ai.orange.task.ClaimService.ReleaseResult;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST surface for the chat-as-executor MCP tools. The Python MCP server in
 * {@code orange-mcp/} thin-shims each endpoint into a tool the chat session
 * calls — claim → heartbeat → complete / fail / release.
 *
 * Wire-format compatibility: response bodies match what
 * {@code orange-mcp/src/orange_mcp/server.py} expects to receive verbatim.
 */
@RestController
@RequestMapping("/claims")
public class ClaimController {

    private final ClaimService claims;
    private final TaskRepository tasks;
    private final PlannerService planner;
    private final int maxAttempts;

    public ClaimController(ClaimService claims,
                            TaskRepository tasks,
                            PlannerService planner,
                            @Value("${orange.claims.max-attempts:3}") int maxAttempts) {
        this.claims = claims;
        this.tasks = tasks;
        this.planner = planner;
        this.maxAttempts = maxAttempts;
    }

    @PostMapping("/next")
    public ResponseEntity<ClaimResponse> claimNext(@Valid @RequestBody ClaimNextRequest req) {
        Optional<ClaimedTask> claimed = claims.claimNext(req.role(), req.workerId());
        return claimed.map(c -> ResponseEntity.status(201).body(ClaimResponse.of(c)))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{token}/heartbeat")
    public ResponseEntity<HeartbeatResponse> heartbeat(@PathVariable UUID token) {
        HeartbeatResult r = claims.heartbeat(token);
        if (!r.alive()) return ResponseEntity.status(410).body(HeartbeatResponse.of(r));
        return ResponseEntity.ok(HeartbeatResponse.of(r));
    }

    @PostMapping("/{token}/complete")
    public ResponseEntity<Map<String, Object>> complete(@PathVariable UUID token,
                                                         @RequestBody(required = false) CompleteClaimRequest req) {
        CompleteResult r = claims.complete(token,
                req == null ? null : req.summary(),
                req == null ? null : req.artifacts());
        if (!r.ok()) {
            return ResponseEntity.status(410).body(Map.of(
                    "ok", false,
                    "reason", "claim token not found or already released"));
        }
        Task t = tasks.findById(r.taskId()).orElseThrow();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("taskId", r.taskId());
        body.put("newStatus", t.status().dbValue());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{token}/fail")
    public ResponseEntity<Map<String, Object>> fail(@PathVariable UUID token,
                                                     @RequestBody(required = false) FailClaimRequest req) {
        boolean retryable = req != null && Boolean.TRUE.equals(req.retryable());
        String reason = req == null ? null : req.reason();
        FailResult r = claims.fail(token, reason, retryable, maxAttempts);
        if (!r.ok()) {
            return ResponseEntity.status(410).body(Map.of(
                    "ok", false,
                    "reason", "claim token not found or already released"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("taskId", r.taskId());
        body.put("newStatus", r.newStatus().dbValue());
        body.put("retryable", retryable);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/{token}/release")
    public ResponseEntity<Map<String, Object>> release(@PathVariable UUID token,
                                                        @RequestBody(required = false) ReleaseClaimRequest req) {
        ReleaseResult r = claims.release(token, req == null ? null : req.reason());
        if (!r.ok()) {
            return ResponseEntity.status(410).body(Map.of(
                    "ok", false,
                    "reason", "claim token not found or already released"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("taskId", r.taskId());
        body.put("newStatus", "ready");
        return ResponseEntity.ok(body);
    }

    /** Active claims by worker id — chat sessions use this to resume after restart. */
    @GetMapping("/active")
    public ResponseEntity<List<ActiveClaimResponse>> active(@RequestParam String workerId) {
        return ResponseEntity.ok(claims.activeClaims(workerId).stream()
                .map(ActiveClaimResponse::of)
                .toList());
    }

    /** Chat-driven planner submits a {@link com.ai.orange.planner.PlanJson} produced interactively. */
    @PostMapping("/{token}/submit-plan")
    public ResponseEntity<PlanResponse> submitPlan(@PathVariable UUID token,
                                                    @Valid @RequestBody SubmitPlanRequest req) throws IOException {
        if (!token.equals(req.claimToken())) {
            return ResponseEntity.badRequest().build();
        }
        // Submit the plan via the same path as PlannerService.plan, then
        // complete the planner task with the resulting graph as artifacts.
        PlanResult result = planner.submitPlan(req.plan(), req.architectureMd(), req.defaultPipeline());
        Map<String, Object> artifacts = new LinkedHashMap<>();
        artifacts.put("plan_output_dir", result.outputDir().toString());
        artifacts.put("created_task_ids", result.createdTasks().stream().map(Task::id).toList());
        claims.complete(token, "planner submitted " + result.createdTasks().size() + " task(s)", artifacts);
        int code = "success".equals(result.status()) ? 201 : 422;
        return ResponseEntity.status(code).body(PlanResponse.of(result));
    }

    /**
     * Invalid plan input (unknown pipeline, missing key, etc.) maps to 422 so
     * the MCP shim can surface the reason verbatim to the chat user. We scope
     * the handler to this controller — global validation errors stay on the
     * generic 400 path.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidPlan(IllegalArgumentException e) {
        return ResponseEntity.status(422).body(Map.of(
                "ok", false,
                "status", "invalid_plan",
                "reason", e.getMessage()));
    }
}
