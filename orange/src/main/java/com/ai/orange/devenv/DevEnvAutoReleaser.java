package com.ai.orange.devenv;

import com.ai.orange.json.Json;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskEventRepository;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
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
 * Periodic safety-net that releases dev_env leases whose task can no longer be
 * using them: the holder's own release path normally frees the env, and this
 * catches the cases where it didn't (the coordinator crashed with an env
 * leased, the task was cancelled while holding one). Dev-env leasing is
 * dormant until UI QA runs (v2 plan, PR 8).
 *
 * <p>The releaser only touches leases whose holder follows the {@code task-<uuid>}
 * convention. Anything else (manual operator lease, future non-task holders) is
 * left alone — the lease was set up by some other code path and that code path
 * owns the release.
 *
 * <p>Release boundary: any task status where no run of the task is working —
 * {@link TaskStatus#IN_REVIEW}, {@link TaskStatus#MERGED}, {@link TaskStatus#NEEDS_HUMAN},
 * {@link TaskStatus#FAILED}, {@link TaskStatus#CANCELLED}. {@code PENDING},
 * {@code READY} and {@code RUNNING} are skipped on purpose.
 */
@Service
public class DevEnvAutoReleaser {

    private static final Logger log = LoggerFactory.getLogger(DevEnvAutoReleaser.class);

    /** Holder string convention used when a task leases an env. */
    static final String TASK_HOLDER_PREFIX = "task-";

    /** Statuses where no run of the task is working, so its env is safe to reclaim. */
    static final Set<TaskStatus> RELEASE_BOUNDARY = EnumSet.of(
            TaskStatus.IN_REVIEW,
            TaskStatus.MERGED,
            TaskStatus.NEEDS_HUMAN,
            TaskStatus.FAILED,
            TaskStatus.CANCELLED);

    private final DevEnvRepository envs;
    private final DevEnvService envService;
    private final EnvComposeRunner composeRunner;
    private final TaskRepository tasks;
    private final TaskEventRepository taskEvents;
    private final boolean enabled;

    public DevEnvAutoReleaser(DevEnvRepository envs,
                              DevEnvService envService,
                              EnvComposeRunner composeRunner,
                              TaskRepository tasks,
                              TaskEventRepository taskEvents,
                              @Value("${orange.envs.auto-release-enabled:true}") boolean enabled) {
        this.envs = envs;
        this.envService = envService;
        this.composeRunner = composeRunner;
        this.tasks = tasks;
        this.taskEvents = taskEvents;
        this.enabled = enabled;
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
            // Race with the holder's own release path — already returned.
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
            return JSONB.valueOf(Json.mapper().writeValueAsString(payload));
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
