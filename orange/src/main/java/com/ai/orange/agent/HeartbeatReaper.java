package com.ai.orange.agent;

import static com.ai.orange.db.jooq.Tables.AGENT_RUNS;

import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Periodically finds tasks stuck {@code in_progress} whose {@code heartbeat_at}
 * column is too old and marks both them and their active {@code agent_runs} as
 * {@code failed}. Required because the orchestrator can crash mid-run; without
 * a reaper the orphaned task would stay claimed forever.
 *
 * Heartbeats themselves are written by {@code AgentRunnerProcess} consumers in
 * Phase 3 (every event from the runner bumps the timestamp).
 */
@Service
public class HeartbeatReaper {

    private static final Logger log = LoggerFactory.getLogger(HeartbeatReaper.class);

    private final TaskRepository tasks;
    private final DSLContext dsl;
    private final Duration staleAfter;

    public HeartbeatReaper(TaskRepository tasks,
                           DSLContext dsl,
                           @Value("${orange.heartbeat.stale-after:5m}") Duration staleAfter) {
        this.tasks = tasks;
        this.dsl = dsl;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${orange.heartbeat.poll-delay:30s}",
               initialDelayString = "${orange.heartbeat.initial-delay:30s}")
    @Transactional
    public void reap() {
        OffsetDateTime threshold = OffsetDateTime.now().minus(staleAfter);
        List<Task> stale = tasks.findStaleInProgress(threshold);
        if (stale.isEmpty()) return;

        log.warn("reaping {} stale in_progress tasks (heartbeat older than {})", stale.size(), threshold);
        for (Task t : stale) {
            tasks.transitionStatus(t.id(), TaskStatus.IN_PROGRESS, TaskStatus.FAILED);
            // Best-effort: any active agent_run for this task gets failed too.
            dsl.update(AGENT_RUNS)
                    .set(AGENT_RUNS.STATUS, AgentRunStatus.FAILED.dbValue())
                    .set(AGENT_RUNS.FINISHED_AT, OffsetDateTime.now())
                    .where(AGENT_RUNS.TASK_ID.eq(t.id())
                            .and(AGENT_RUNS.STATUS.eq(AgentRunStatus.RUNNING.dbValue())))
                    .execute();
        }
    }
}
