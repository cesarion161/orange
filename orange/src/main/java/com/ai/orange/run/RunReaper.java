package com.ai.orange.run;

import com.ai.orange.harness.container.ContainerRuntime;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Ends runs nobody is supervising: leases that ran out, and runs a previous coordinator instance
 * left running (their harness process is killed first). Neither counts against the task; the
 * reactor then queues a fresh run.
 *
 * <p>In container mode the run's containers go too, found by the {@code orange.run} label of the
 * rows this database calls expired or orphaned: another coordinator may share the Docker daemon,
 * and the run id is the only name that is surely ours.
 */
@Component
public class RunReaper {

    private static final Logger log = LoggerFactory.getLogger(RunReaper.class);
    /** A live process is an agent run's harness only if it started this close to the run's started_at. */
    static final Duration STARTED_BEFORE = Duration.ofSeconds(5);
    static final Duration STARTED_AFTER = Duration.ofSeconds(60);

    private final RunRepository runs;
    private final RunsProperties props;
    private final ContainerRuntime containers;

    public RunReaper(RunRepository runs, RunsProperties props, ContainerRuntime containers) {
        this.runs = runs;
        this.props = props;
        this.containers = containers;
    }

    /** Fails every running run whose lease has expired. */
    public void reapExpired() {
        for (Run r : runs.findExpiredLeases(OffsetDateTime.now())) {
            removeContainers(r);
            if (runs.finish(r.id(), RunStatus.FAILED, "lease_expired", r.costUsd(), 0, 0, 0, null)) {
                log.warn("run {} ({}, task {}) lost its lease at {}; failed it", r.id(), r.kind().dbValue(),
                        r.taskId(), r.leaseExpiresAt());
            }
        }
    }

    /** Fails every running run another instance started, killing its process if it is still alive. */
    public void failOrphans() {
        for (Run r : runs.findRunningNotOwnedBy(props.workerId())) {
            // For a contained run the pid is the docker client's; the container outlives it.
            if (r.pid() != null) killIfItsHarness(r);
            removeContainers(r);
            if (runs.finish(r.id(), RunStatus.FAILED, "coordinator_restart", r.costUsd(), 0, 0, 0, null)) {
                log.warn("run {} ({}, task {}) was left running by {}; failed it", r.id(), r.kind().dbValue(),
                        r.taskId(), r.workerId());
            }
        }
    }

    /** Before the row is finished: the task's next run must not find the old one's containers still at work. */
    private void removeContainers(Run r) {
        if (!containers.enabled()) return;
        int removed = containers.removeByRun(r.id());
        if (removed > 0) log.warn("run {}: removed its {} container(s)", r.id(), removed);
    }

    /** Pids get reused: only a process that started around the run's own start is killed. */
    private static void killIfItsHarness(Run r) {
        Optional<ProcessHandle> process = ProcessHandle.of(r.pid()).filter(ProcessHandle::isAlive);
        if (process.isEmpty()) return;
        Optional<Instant> started = process.get().info().startInstant();
        if (started.isEmpty() || r.startedAt() == null) {
            log.warn("run {}: cannot tell whether pid {} is its harness; leaving it", r.id(), r.pid());
            return;
        }
        Instant runStart = r.startedAt().toInstant();
        // An agent's harness starts with its run. A verify run starts its commands one after another,
        // so its latest command may have started any time while the run held its lease.
        Instant latest = r.kind() == RunKind.VERIFY
                ? (r.leaseExpiresAt() != null ? r.leaseExpiresAt().toInstant() : Instant.now())
                : runStart.plus(STARTED_AFTER);
        if (started.get().isBefore(runStart.minus(STARTED_BEFORE)) || started.get().isAfter(latest)) {
            log.info("run {}: pid {} started at {}, not within the run ({} .. {}); not killing it", r.id(), r.pid(),
                    started.get(), runStart, latest);
            return;
        }
        log.warn("run {}: killing its orphaned harness, pid {}", r.id(), r.pid());
        process.get().descendants().forEach(ProcessHandle::destroyForcibly);
        process.get().destroyForcibly();
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (props.loopEnabled()) failOrphans();
    }

    @Scheduled(fixedDelayString = "${orange.runs.reaper-sweep:30s}")
    void sweep() {
        if (!props.loopEnabled()) return;
        try {
            reapExpired();
        } catch (RuntimeException e) {
            log.warn("reaping expired leases failed: {}", e.toString());
        }
    }
}
