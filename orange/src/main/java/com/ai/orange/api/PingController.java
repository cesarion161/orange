package com.ai.orange.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Simple liveness probe. {@code GET /api/ping} returns a JSON body with a
 * static {@code pong:true} flag and the current server timestamp — useful for
 * smoke-testing routing and clock skew without touching downstream
 * dependencies.
 */
@RestController
public class PingController {

    @GetMapping("/api/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        return ResponseEntity.ok(Map.of(
                "pong", true,
                "timestamp", Instant.now().toString()));
    }
}
