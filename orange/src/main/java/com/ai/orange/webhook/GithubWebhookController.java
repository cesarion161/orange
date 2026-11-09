package com.ai.orange.webhook;

import com.ai.orange.events.EventRepository;
import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * GitHub's webhook endpoint. The HMAC filter has already checked the signature; the delivery is
 * stored in the events inbox (once per delivery id) and processed later by the
 * {@link com.ai.orange.events.EventProcessor}, so a reply never waits on processing.
 */
@RestController
public class GithubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GithubWebhookController.class);

    private final EventRepository events;

    public GithubWebhookController(EventRepository events) {
        this.events = events;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Map<String, Object>> receive(
            @RequestHeader(name = "X-GitHub-Event", required = false) String event,
            @RequestHeader(name = "X-GitHub-Delivery", required = false) String delivery,
            @RequestBody(required = false) String payload) {
        String body = payload == null ? "" : payload;
        String deliveryId = delivery != null && !delivery.isBlank() ? delivery.strip() : "body-" + sha256(body);
        JSONB json;
        String repo = null;
        try {
            JsonNode root = Json.mapper().readTree(body.isBlank() ? "{}" : body);
            json = JSONB.valueOf(root.toString());
            if (root.path("repository").hasNonNull("full_name")) repo = root.path("repository").path("full_name").asText();
        } catch (Exception e) {
            // Not JSON (a form-encoded hook, say): keep the raw text so nothing is lost.
            json = JSONB.valueOf(Json.mapper().createObjectNode().put("raw", body).toString());
        }
        boolean stored = events.insert("github", event == null || event.isBlank() ? "unknown" : event, repo, deliveryId,
                json);
        log.info("GitHub webhook {} delivery={} repo={} {}", event, deliveryId, repo, stored ? "stored" : "already stored");
        return ResponseEntity.ok(Map.of("stored", stored));
    }

    private static String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
