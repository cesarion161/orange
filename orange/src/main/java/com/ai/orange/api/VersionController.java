package com.ai.orange.api;

import java.util.Map;
import org.springframework.core.SpringVersion;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the runtime Java and Spring Boot versions for diagnostic purposes.
 */
@RestController
public class VersionController {

    @GetMapping("/api/version")
    public ResponseEntity<Map<String, String>> version() {
        return ResponseEntity.ok(Map.of(
                "java", System.getProperty("java.version"),
                "springBoot", SpringVersion.getVersion()));
    }
}
