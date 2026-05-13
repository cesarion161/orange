package com.ai.orange.api;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public app-info endpoint. Returns a small JSON identifying the running
 * service and its declared version. Intended for lightweight liveness/probe
 * checks where {@code /actuator/info} is overkill.
 */
@RestController
public class InfoController {

    @GetMapping("/api/info")
    public ResponseEntity<Map<String, String>> info() {
        return ResponseEntity.ok(Map.of(
                "app", "orange",
                "version", "0.0.1-SNAPSHOT"));
    }
}
