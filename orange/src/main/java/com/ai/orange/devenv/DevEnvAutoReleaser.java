package com.ai.orange.devenv;

import com.ai.orange.agent.protocol.ProtocolCodec;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import com.ai.orange.workflow.EnvInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Periodic safety-net that releases dev_env leases whose task has clearly
 * moved past the env-needing stage. The workflow's own try/finally release
 * still fires on the happy path — this disposer catches the cases where it
 * didn't:
 *
 * <ul>
 *   <li>Orchestrator crashed mid-stage with an env still leased.</li>
 *   <li>Task was cancelled while the env was held.</li>
 *   <li>Future chat-as-executor flows that lease envs but skip the workflow's
 *       finally block.</li>
 * </ul>
 *
 * <p>The releaser only touches leases whose holder follows the {@code task-<uuid>}
 * convention written by {@link com.ai.orange.workflow.PipelineActivitiesImpl#acquireDevEnv}.
 * Anything else (manual operator lease, future non-task holders) is left
 * alone — the lease was set up by some other code path and that code path
 * owns the release.
 *
 * <p>Release boundary: any task status that's downstream of the qa stage —
 * {@link TaskStatus#PR_OPEN}, {@link TaskStatus#DEV_READY},
 * {@link TaskStatus#IN_TEST}, {@link TaskStatus#TEST_DONE},
 * {@link TaskStatus#FAILED}, {@link TaskStatus#CANCELLED}. The purely active
 * states {@code PENDING} and {@code READY} are skipped on purpose. {@code
 * IN_PROGRESS} tasks are also left alone <em>unless</em> their {@code
 * heartbeat_at} is older than {@code orange.heartbeat.stale-after} (default
 * 5 m), which indicates the workflow is dead; those envs are reclaimed with
 * reason {@code stale_heartbeat}. {@code IN_PROGRESS} tasks that have never
 * written a heartbeat ({@code heartbeat_at} is {@code null}) are treated as
 * not-yet-stale and skipped.
 */
@Service
public class DevEnvAutoReleaser {

    private static final Logger log = LoggerFactory.getLogger(DevEnvAutoReleaser.class);

    /** Holder string convention used by the workflow's acquire path. */
    static final String TASK_HOLDER_PREFIX = "task-";

    /** Statuses where the env is downstream of the qa stage and is safe to reclaim. */
    static final Set<TaskStatus> RELEASE_BOUNDARY = EnumSet.of(
            TaskStatus.PR_OPEN,
            TaskStatus.DEV_READY,
            TaskStatus.IN_TEST,
            TaskStatus.TEST_DONE,
            TaskStatus.FAILED,
            TaskStatus.CANCELLED);

    private final DevEnvRepository envs;
    private final DevEnvService envService;
    private final EnvComposeRunner composeRunner;
    private final TaskRepository tasks;
    private final TaskEventRepository taskEvents;
    private final boolean enabled;
    private final Duration staleAfter;

    public DevEnvAutoReleaser(DevEnvRepository envs,
                              DevEnvService envService,
                              EnvComposeRunner composeRunner,
                              TaskRepository tasks,
                              TaskEventRepository taskEvents,
                              @Value("${orange.envs.auto-release-enabled:true}") boolean enabled,
                              @Value("${orange.heartbeat.stale-after:5m}") Duration staleAfter) {
        this.envs = envs;
        this.envService = envService;
        this.composeRunner = composeRunner;
        this.tasks = tasks;
        this.taskEvents = taskEvents;
        this.enabled = enabled;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${orange.envs.auto-release-delay:1m}",
               initialDelayString = "${orange.envs.auto-release-initial-delay:30s}")
    public void sweep() {
        if (!enabled) return;
        int released = sweepOnce();
        if (released > 0) log.info("dev-env auto-release: freed {} stale lease(s)", released);
    }

    /**
     * One pass over the leased pool. Returns the number of envs released so
     * the integration test can assert progress without waiting for the next
     * scheduled tick.
     */
    public int sweepOnce() {
        List<DevEnv> leased = envs.findLeased();
        int released = 0;
        for (DevEnv env : leased) {
            UUID taskId = parseTaskHolder(env.leasedBy());
            if (taskId == null) {
                log.debug("auto-release: skipping env {} with non-task holder '{}'",
                        env.name(), env.leasedBy());
                continue;
            }
            Optional<Task> taskOpt = tasks.findById(taskId);
            if (taskOpt.isEmpty()) {
                // Task vanished (e.g. ops-level delete). Reclaim the env so
                // the pool isn't permanently smaller — but skip the task_event
                // append because the foreign key would reject it.
                if (releaseEnv(env, taskId, "task_missing", false)) released++;
                continue;
            }
            Task task = taskOpt.get();
            TaskStatus status = task.status();
            // IN_PROGRESS with a stale heartbeat means the workflow is dead;
            // reclaim the env. Null heartbeat_at (task never checked in) is
            // treated as not-yet-stale and skipped.
            if (status == TaskStatus.IN_PROGRESS
                    && task.heartbeatAt() != null
                    && task.heartbeatAt().isBefore(OffsetDateTime.now().minus(staleAfter))) {
                if (releaseEnv(env, taskId, "stale_heartbeat", true)) released++;
                continue;
            }
            if (!RELEASE_BOUNDARY.contains(status)) continue;
            if (releaseEnv(env, taskId, status.dbValue(), true)) released++;
        }
        return released;
    }

    private boolean releaseEnv(DevEnv env, UUID taskId, String reason, boolean writeEvent) {
        EnvInfo info = EnvInfo.from(env);

        // Tear down the compose stack first so containers don't outlive the
        // lease. Failures are logged but don't block the lease release —
        // composeRunner.down already swallows IOExceptions internally.
        try {
            composeRunner.down(info);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("auto-release: compose down for env {} threw: {}", env.name(), e.getMessage());
        }

        boolean ok = envService.release(env.id());
        if (!ok) {
            // Race with the workflow's own release path — already returned.
            // Not an error; the auto-releaser is a backstop.
            log.debug("auto-release: env {} already released by another path", env.name());
            return false;
        }
        log.info("auto-release: env {} (task {}, reason={})", env.name(), taskId, reason);
        if (writeEvent) {
            taskEvents.append(taskId, "auto-releaser", "env_auto_released",
                    asJsonb(Map.of(
                            "env_id", env.id().toString(),
                            "env_name", env.name(),
                            "reason", reason)));
        }
        return true;
    }

    /** Serialize a payload map to JSONB. Jackson handles escaping so an env
     *  name containing a quote or backslash doesn't corrupt the row. */
    private static JSONB asJsonb(Map<String, Object> payload) {
        try {
            return JSONB.valueOf(ProtocolCodec.mapper().writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            // Single-level map of well-known strings — should be unreachable,
            // but fall back to an empty object so the release still records.
            log.warn("auto-release: failed to serialize event payload: {}", e.getMessage());
            return JSONB.valueOf("{}");
        }
    }

    static UUID parseTaskHolder(String holder) {
        if (holder == null || !holder.startsWith(TASK_HOLDER_PREFIX)) return null;
        try {
            return UUID.fromString(holder.substring(TASK_HOLDER_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
