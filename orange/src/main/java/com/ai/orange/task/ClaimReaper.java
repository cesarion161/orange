package com.ai.orange.task;

import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically scans {@code task_claims} for expired leases and either bounces
 * the orphaned task back to {@code ready} (if attempts remain) or marks it
 * {@code failed}. This is the chat-driven equivalent of {@link com.ai.orange.agent.HeartbeatReaper}:
 * a chat session that simply walked away from a task can't be detected by the
 * orchestrator, so we time out the lease and let the next worker pick the task
 * up.
 */
@Service
public class ClaimReaper {

    private static final Logger log = LoggerFactory.getLogger(ClaimReaper.class);

    private final TaskClaimRepository claims;
    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final int maxAttempts;

    public ClaimReaper(TaskClaimRepository claims,
                       TaskRepository tasks,
                       TaskEventRepository events,
                       @Value("${orange.claims.max-attempts:3}") int maxAttempts) {
        this.claims = claims;
        this.tasks = tasks;
        this.events = events;
        this.maxAttempts = maxAttempts;
    }

    @Scheduled(fixedDelayString = "${orange.claims.reaper-delay:30s}",
               initialDelayString = "${orange.claims.reaper-initial-delay:30s}")
    @Transactional
    public void reap() {
        OffsetDateTime now = OffsetDateTime.now();
        List<TaskClaim> expired = claims.findExpired(now);
        if (expired.isEmpty()) return;

        log.warn("reaping {} expired task_claims (now={})", expired.size(), now);
        for (TaskClaim c : expired) {
            Task task = tasks.findById(c.taskId()).orElse(null);
            if (task == null) {
                claims.deleteByToken(c.claimToken());
                continue;
            }
            TaskStatus next = (c.attempt() < maxAttempts) ? TaskStatus.READY : TaskStatus.FAILED;
            boolean transitioned = false;
            if (task.status() == TaskStatus.IN_PROGRESS) {
                try {
                    transitioned = tasks.transitionStatus(task.id(), TaskStatus.IN_PROGRESS, next);
                } catch (Exception e) {
                    log.warn("reaper transition failed for task {}: {}", task.id(), e.getMessage());
                }
            }
            events.append(task.id(), "system", "claim_expired",
                    org.jooq.JSONB.valueOf("{"
                            + "\"claim_token\":\"" + c.claimToken() + "\","
                            + "\"worker_id\":\"" + escape(c.workerId()) + "\","
                            + "\"attempt\":" + c.attempt() + ","
                            + "\"transitioned_to\":\"" + (transitioned ? next.dbValue() : task.status().dbValue()) + "\""
                            + "}"));
            claims.deleteByToken(c.claimToken());
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
