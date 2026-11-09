package com.ai.orange.api;

import com.ai.orange.run.RunEventRepository;
import com.ai.orange.run.RunRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only views of runs and their event streams. */
@RestController
@RequestMapping("/runs")
public class RunController {

    private final RunRepository runs;
    private final RunEventRepository runEvents;

    public RunController(RunRepository runs, RunEventRepository runEvents) {
        this.runs = runs;
        this.runEvents = runEvents;
    }

    @GetMapping("/{id}")
    public ResponseEntity<RunResponse> get(@PathVariable UUID id) {
        return runs.findById(id).map(r -> ResponseEntity.ok(RunResponse.of(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** The run's events with {@code seq > since}, oldest first; at most {@code limit} (≤ 500). */
    @GetMapping("/{id}/events")
    public ResponseEntity<List<RunEventResponse>> events(@PathVariable UUID id,
                                                         @RequestParam(defaultValue = "0") int since,
                                                         @RequestParam(defaultValue = "100") int limit) {
        if (runs.findById(id).isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(runEvents.findByRunSince(id, since, limit).stream().map(RunEventResponse::of).toList());
    }
}
