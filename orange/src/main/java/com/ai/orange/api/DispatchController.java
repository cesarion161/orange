package com.ai.orange.api;

import com.ai.orange.dispatch.DispatchService;
import com.ai.orange.dispatch.DispatchService.DispatchResult;
import com.ai.orange.dispatch.PlanNotFoundException;
import com.ai.orange.task.exception.DagCycleException;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dispatcher half of the planner/dispatcher split: ingests a plan (inline
 * or by saved {@code planId}) and runs it. Independent of how the plan was
 * produced — see {@link com.ai.orange.dispatch.DispatchService}.
 */
@RestController
@RequestMapping("/dispatch")
public class DispatchController {

    private final DispatchService dispatch;

    public DispatchController(DispatchService dispatch) {
        this.dispatch = dispatch;
    }

    @PostMapping
    public ResponseEntity<DispatchResponse> dispatch(@Valid @RequestBody DispatchRequest req) throws IOException {
        boolean hasPlan = req.plan() != null;
        boolean hasId = req.planId() != null;
        if (hasPlan == hasId) {
            throw new IllegalArgumentException("exactly one of 'plan' or 'planId' is required");
        }
        DispatchResult result = hasPlan
                ? dispatch.dispatch(req.plan(), req.architectureMd(), req.defaultPipeline())
                : dispatch.dispatchByPlanId(req.planId(), req.defaultPipeline());
        return ResponseEntity.status(201).body(DispatchResponse.of(result));
    }

    /** Missing saved plan → 404. */
    @ExceptionHandler(PlanNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(PlanNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of(
                "ok", false,
                "status", "plan_not_found",
                "reason", e.getMessage()));
    }

    /**
     * Invalid plan (bad input, unknown pipeline, cycle) → 422 so the MCP shim
     * can surface the reason verbatim. Mirrors the handler in ClaimController.
     */
    @ExceptionHandler({IllegalArgumentException.class, DagCycleException.class})
    public ResponseEntity<Map<String, Object>> handleInvalidPlan(RuntimeException e) {
        return ResponseEntity.status(422).body(Map.of(
                "ok", false,
                "status", "invalid_plan",
                "reason", e.getMessage()));
    }
}
