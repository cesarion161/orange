package com.ai.orange.postmerge;

import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.worktree.WorktreeProperties;
import com.ai.orange.worktree.WorktreeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodic sweep that removes worktrees of tasks in terminal status. The
 * happy-path workflow already calls {@code cleanupWorktree} after each run,
 * so most worktrees are gone before the disposer ever sees them — this is a
 * backstop for:
 *
 * <ul>
 *   <li>orchestrator crashes mid-cleanup</li>
 *   <li>chat-as-executor tasks that didn't go through the workflow</li>
 *   <li>worktrees left behind by an in-flight task that was cancelled</li>
 * </ul>
 *
 * Each successful disposal stamps {@code metadata.worktree_removed_at} on the
 * task row, so subsequent passes skip the row instead of retrying a removed
 * worktree.
 */
@Service
public class WorktreeDisposer {

    private static final Logger log = LoggerFactory.getLogger(WorktreeDisposer.class);

    private final TaskRepository tasks;
    private final TaskEventRepository events;
    private final WorktreeService worktrees;
    private final WorktreeProperties worktreeProps;
    private final ObjectMapper json = new ObjectMapper();

    public WorktreeDisposer(TaskRepository tasks,
                            TaskEventRepository events,
                            WorktreeService worktrees,
                            WorktreeProperties worktreeProps) {
        this.tasks = tasks;
        this.events = events;
        this.worktrees = worktrees;
        this.worktreeProps = worktreeProps;
    }

    @Scheduled(fixedDelayString = "${orange.worktrees.disposer-delay:5m}",
               initialDelayString = "${orange.worktrees.disposer-initial-delay:1m}")
    public void sweep() {
        if (worktreeProps.baseRepo() == null || worktreeProps.baseRepo().isBlank()) {
            // No baseRepo means worktrees are never created → nothing to dispose.
            return;
        }
        List<Task> candidates = tasks.findTerminalWithLiveWorktree();
        if (candidates.isEmpty()) return;

        log.info("worktree disposer: {} terminal task(s) with live worktrees", candidates.size());
        for (Task t : candidates) {
            try {
                worktrees.remove(t.id());
                stampRemoved(t);
                events.append(t.id(), "orchestrator", "worktree_disposed",
                        asJsonb(Map.of("status", t.status().dbValue())));
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("dispose worktree for task {} failed: {}", t.id(), e.getMessage());
                // Leave metadata flag unset so the next sweep retries.
            }
        }
    }

    private void stampRemoved(Task t) {
        Map<String, Object> meta = parseMetadata(t.metadata());
        meta.put("worktree_removed_at", OffsetDateTime.now().toString());
        tasks.updateMetadata(t.id(), asJsonb(meta));
    }

    private Map<String, Object> parseMetadata(JSONB meta) {
        if (meta == null) return new HashMap<>();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = json.readValue(meta.data(), Map.class);
            return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private JSONB asJsonb(Map<String, Object> map) {
        try {
            return JSONB.valueOf(json.writeValueAsString(map));
        } catch (JsonProcessingException e) {
            return JSONB.valueOf("{}");
        }
    }
}
