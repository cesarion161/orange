package com.ai.orange.github;

import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Translates GitHub webhook payloads into orchestrator state changes:
 *
 * <ul>
 *   <li>{@code pull_request.closed} with {@code merged=true} → the owning task
 *       goes from {@code in_review} to {@code merged}, which promotes its dependents.</li>
 *   <li>{@code pull_request_review.submitted} → logged only.</li>
 * </ul>
 *
 * Task lookup uses the task's {@code repo}/{@code pr_number} columns (recorded
 * by {@link GithubPrService}). If that misses, the body's
 * {@code <!-- orange-task: <uuid> --> } marker is used as a fallback.
 */
@Service
public class GithubEventRouter {

    private static final Logger log = LoggerFactory.getLogger(GithubEventRouter.class);

    private final TaskRepository tasks;
    private final ObjectMapper mapper = new ObjectMapper();

    public GithubEventRouter(TaskRepository tasks) {
        this.tasks = tasks;
    }

    /**
     * @param eventType the {@code X-GitHub-Event} header
     * @param body      raw webhook body (already HMAC-verified upstream)
     */
    public void route(String eventType, String body) {
        if (eventType == null || body == null || body.isBlank()) return;
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            log.warn("could not parse webhook body: {}", e.getMessage());
            return;
        }

        switch (eventType) {
            case "pull_request" -> handlePullRequest(root);
            case "pull_request_review" -> handlePullRequestReview(root);
            default -> log.debug("ignoring event type {}", eventType);
        }
    }

    private void handlePullRequest(JsonNode root) {
        String action = textOrNull(root, "action");
        if (!"closed".equals(action)) return;
        JsonNode pr = root.get("pull_request");
        if (pr == null) return;
        boolean merged = pr.path("merged").asBoolean(false);
        if (!merged) {
            log.info("PR closed without merge; ignoring");
            return;
        }
        Task task = locateTask(root, pr).orElse(null);
        if (task == null) {
            log.info("merged PR has no matching orange task; ignoring");
            return;
        }
        if (task.status() != TaskStatus.IN_REVIEW) {
            log.info("PR-merged for task {} but status was {}; not transitioning", task.id(), task.status());
            return;
        }
        boolean ok = tasks.transitionStatus(task.id(), TaskStatus.IN_REVIEW, TaskStatus.MERGED);
        if (ok) {
            log.info("task {} transitioned IN_REVIEW → MERGED on PR merge", task.id());
        } else {
            log.warn("task {} could not be transitioned (raced)", task.id());
        }
    }

    private void handlePullRequestReview(JsonNode root) {
        String action = textOrNull(root, "action");
        if (!"submitted".equals(action)) return;
        JsonNode review = root.get("review");
        String state = review == null ? null : textOrNull(review, "state");
        Task task = locateTask(root, root.get("pull_request")).orElse(null);
        if (task == null) {
            log.debug("review event has no matching orange task; ignoring");
            return;
        }
        log.info("review submitted on task {} state={} (Phase 5 logs only; Phase 6+ will signal)",
                task.id(), state);
    }

    private Optional<Task> locateTask(JsonNode rootEvent, JsonNode pullRequest) {
        if (pullRequest == null) return Optional.empty();
        String repo = repoFullName(rootEvent);
        int number = pullRequest.path("number").asInt(-1);
        if (repo != null && number > 0) {
            Optional<Task> byPr = tasks.findByPullRequest(repo, number);
            if (byPr.isPresent()) return byPr;
        }
        // Fallback: parse the orange-task marker out of the PR body.
        String body = textOrNull(pullRequest, "body");
        Optional<UUID> bodyId = GithubPrService.extractTaskIdFromBody(body);
        return bodyId.flatMap(tasks::findById);
    }

    private static String repoFullName(JsonNode rootEvent) {
        JsonNode repo = rootEvent.get("repository");
        if (repo == null) return null;
        return textOrNull(repo, "full_name");
    }

    private static String textOrNull(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode child = node.get(field);
        return (child == null || child.isNull()) ? null : child.asText();
    }
}
