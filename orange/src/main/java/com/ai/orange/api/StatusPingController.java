package com.ai.orange.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight liveness ping consumed by the dev_qa pipeline smoke check.
 *
 * <p>{@code GET /api/status} → {@code {"ok":true,"service":"orange"}}
 */
@RestController
@RequestMapping("/api")
public class StatusPingController {

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("service", "orange");
        return ResponseEntity.ok(body);
    }
}
