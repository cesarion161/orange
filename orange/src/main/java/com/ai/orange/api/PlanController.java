package com.ai.orange.api;

import com.ai.orange.planner.PlannerService;
import com.ai.orange.planner.PlannerService.PlanResult;
import jakarta.validation.Valid;
import java.io.IOException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/plans")
public class PlanController {

    private final PlannerService planner;

    public PlanController(PlannerService planner) {
        this.planner = planner;
    }

    @PostMapping
    public ResponseEntity<PlanResponse> plan(@Valid @RequestBody PlanRequest req) throws IOException, InterruptedException {
        PlanResult result = planner.plan(req.description());
        int code = "success".equals(result.status()) ? 201 : 422;
        return ResponseEntity.status(code).body(PlanResponse.of(result));
    }
}
