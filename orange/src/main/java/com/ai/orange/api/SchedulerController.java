package com.ai.orange.api;

import com.ai.orange.run.SchedulerPause;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The coordinator's stop switch and its run counts. */
@RestController
@RequestMapping("/scheduler")
public class SchedulerController {

    private final SchedulerPause pause;
    private final SchedulerView view;

    public SchedulerController(SchedulerPause pause, SchedulerView view) {
        this.pause = pause;
        this.view = view;
    }

    /** {@code {paused: {reason, until, since} | null, runs: {<kind>: {queued, running, slots}}, max_concurrent}} */
    @GetMapping
    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paused", view.paused());
        body.put("runs", view.runs());
        body.put("max_concurrent", view.maxConcurrent());
        return body;
    }

    /** Pauses until resumed, or for {@code minutes}. The reason defaults to {@code operator}. */
    @PostMapping("/pause")
    public Map<String, Object> pause(@RequestBody(required = false) PauseRequest request) {
        String reason = request == null || request.reason() == null || request.reason().isBlank()
                ? "operator" : request.reason().strip();
        OffsetDateTime until = request == null || request.minutes() == null || request.minutes() <= 0
                ? null : OffsetDateTime.now().plusMinutes(request.minutes());
        pause.pause(reason, until);
        return status();
    }

    @PostMapping("/resume")
    public Map<String, Object> resume() {
        pause.resume();
        return status();
    }

    public record PauseRequest(String reason, Integer minutes) {}
}
