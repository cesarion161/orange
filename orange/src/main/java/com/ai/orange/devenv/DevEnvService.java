package com.ai.orange.devenv;

import com.ai.orange.listener.PgNotificationEvent;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Blocking front-end on top of {@link DevEnvRepository}. Two purposes:
 *
 * <ol>
 *   <li>{@link #acquire} — atomically claim the next free env, blocking up to
 *       {@code timeout} on Postgres {@code dev_env_freed} notifications when
 *       the pool is exhausted.</li>
 *   <li>{@link #release} — return an env to the pool; the schema's trigger
 *       fires NOTIFY for us.</li>
 * </ol>
 *
 * Notification delivery isn't guaranteed exactly-once for waiters (multiple
 * threads racing on the same notification) but each acquirer also re-checks
 * {@code tryLease} on a short polling tick, so missed wakeups just delay —
 * they never deadlock.
 */
@Service
public class DevEnvService {

    private static final Logger log = LoggerFactory.getLogger(DevEnvService.class);
    private static final Duration POLL_TICK = Duration.ofSeconds(5);

    private final DevEnvRepository repo;
    /**
     * Multi-permit semaphore: each {@code dev_env_freed} notification adds a
     * permit; each waiter consumes a permit when it wakes. We use a
     * {@link LinkedBlockingQueue} for its blocking poll-with-timeout API.
     */
    private final LinkedBlockingQueue<UUID> freedNotifications = new LinkedBlockingQueue<>();

    public DevEnvService(DevEnvRepository repo) {
        this.repo = repo;
    }

    /**
     * Lease the next free env or block up to {@code timeout} waiting for one
     * to be returned. Returns empty on timeout.
     */
    public Optional<DevEnv> acquire(String holder, Duration timeout) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        // Drain any notifications buffered before this acquire was called so
        // we don't wake stale on the very first wait.
        freedNotifications.clear();

        while (true) {
            Optional<DevEnv> env = repo.tryLease(holder);
            if (env.isPresent()) {
                log.info("acquired dev_env {} for holder {}", env.get().name(), holder);
                return env;
            }

            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                log.info("acquire timed out for holder {}", holder);
                return Optional.empty();
            }
            long waitNanos = Math.min(remaining, POLL_TICK.toNanos());
            // poll() returns null on timeout — either way we just retry tryLease.
            freedNotifications.poll(waitNanos, TimeUnit.NANOSECONDS);
        }
    }

    public boolean release(UUID id) {
        boolean ok = repo.release(id);
        if (ok) log.info("released dev_env {}", id);
        return ok;
    }

    @EventListener
    void onPgNotification(PgNotificationEvent event) {
        if (!"dev_env_freed".equals(event.channel())) return;
        UUID envId;
        try {
            envId = UUID.fromString(event.payload());
        } catch (IllegalArgumentException e) {
            log.warn("ignored malformed dev_env_freed payload: {}", event.payload());
            return;
        }
        // Buffer the notification; one waiter will consume it. Other waiters
        // either also see free envs on their own polling tick or get later
        // notifications.
        freedNotifications.offer(envId);
    }
}
