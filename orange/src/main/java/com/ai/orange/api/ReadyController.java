package com.ai.orange.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight readiness probe used by smoke tests and load-balancer health checks.
 *
 * <p>{@code GET /api/ready} returns {@code {"ready":true,"service":"orange"}} with
 * HTTP 200 as long as the application context is up.  No database or downstream
 * dependencies are checked here — use {@code /actuator/health} for deep checks.
 *
 * <p>Field order is pinned via {@link LinkedHashMap} so smoke checks that do
 * string comparisons against the response body don't flake on Jackson
 * reordering keys.
 */
@RestController
@RequestMapping("/api")
public class ReadyController {

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", true);
        body.put("service", "orange");
        return ResponseEntity.ok(body);
    }
}
