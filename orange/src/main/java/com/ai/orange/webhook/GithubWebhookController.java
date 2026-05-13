package com.ai.orange.webhook;

import com.ai.orange.github.GithubEventRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class GithubWebhookController {

    private static final Logger log = LoggerFactory.getLogger(GithubWebhookController.class);

    private final GithubEventRouter router;

    public GithubWebhookController(GithubEventRouter router) {
        this.router = router;
    }

    @PostMapping("/webhooks/github")
    public ResponseEntity<Void> receive(
            @RequestHeader(name = "X-GitHub-Event", required = false) String event,
            @RequestHeader(name = "X-GitHub-Delivery", required = false) String delivery,
            @RequestBody(required = false) String payload) {
        log.info("GitHub webhook accepted: event={} delivery={} bytes={}",
                event, delivery, payload == null ? 0 : payload.length());
        try {
            router.route(event, payload);
        } catch (Exception e) {
            // We always return 202 so GitHub stops retrying; failures are logged.
            log.error("webhook router threw on event={} delivery={}", event, delivery, e);
        }
        return ResponseEntity.accepted().build();
    }
}
