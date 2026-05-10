package com.ai.orange.webhook;

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

    @PostMapping("/webhooks/github")
    public ResponseEntity<Void> receive(
            @RequestHeader(name = "X-GitHub-Event", required = false) String event,
            @RequestHeader(name = "X-GitHub-Delivery", required = false) String delivery,
            @RequestBody(required = false) String payload) {
        log.info("GitHub webhook accepted: event={} delivery={} bytes={}",
                event, delivery, payload == null ? 0 : payload.length());
        // TODO: route to a Temporal signal (`signal_workflow`) based on event type
        // (pull_request_review, issue_comment, pull_request.closed, …).
        return ResponseEntity.accepted().build();
    }
}
