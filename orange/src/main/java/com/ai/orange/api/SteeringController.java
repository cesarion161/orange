package com.ai.orange.api;

import com.ai.orange.status.StatusService;
import com.ai.orange.status.StatusText;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * What the steering tab starts with (remote-first spec §7.5): the plugin's SessionStart hook of a
 * steering session adds this text to the session's context. Wants the API token like the rest of the
 * operator API.
 */
@RestController
public class SteeringController {

    static final MediaType TEXT = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private final StatusService status;

    public SteeringController(StatusService status) {
        this.status = status;
    }

    /** {@code 200 text/plain}: at most {@link StatusText#MAX_CONTEXT_LINES} lines. */
    @GetMapping("/steering/context")
    public ResponseEntity<String> context() {
        return ResponseEntity.ok().contentType(TEXT).body(StatusText.startContext(status.report()));
    }
}
