package com.ai.orange.github;

import com.ai.orange.events.EventHandler;
import com.ai.orange.events.InboxEvent;
import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Processes stored GitHub deliveries. In merge mode orange integrates verified work itself, so
 * no webhook changes a task: merging through a pull request (and with it the merged-PR
 * transition) belongs to the later PR integration mode (project-space spec §11). Until then the
 * deliveries are logged and kept in the inbox for that mode and for CI-as-evidence.
 */
@Component
public class GithubEventHandler implements EventHandler {

    private static final Logger log = LoggerFactory.getLogger(GithubEventHandler.class);

    @Override
    public boolean handles(InboxEvent event) {
        return "github".equals(event.source());
    }

    @Override
    public void handle(InboxEvent event) {
        JsonNode root;
        try {
            root = Json.mapper().readTree(event.payload().data());
        } catch (Exception e) {
            log.warn("github event {} ({}) has an unreadable payload: {}", event.id(), event.type(), e.getMessage());
            return;
        }
        switch (event.type()) {
            case "pull_request" -> log.info("github: {} PR #{} {}{}", event.repo(),
                    root.path("pull_request").path("number").asInt(), root.path("action").asText(),
                    root.path("pull_request").path("merged").asBoolean(false) ? " (merged)" : "");
            case "pull_request_review" -> log.info("github: {} PR #{} review {}", event.repo(),
                    root.path("pull_request").path("number").asInt(), root.path("review").path("state").asText());
            default -> log.debug("github: {} event {} for {}", event.type(), event.id(), event.repo());
        }
    }
}
