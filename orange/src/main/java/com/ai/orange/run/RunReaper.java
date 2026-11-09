package com.ai.orange.run;

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
 */
@Component
public class RunReaper {

    private static final Logger log = LoggerFactory.getLogger(RunReaper.class);
    /** A live process is a run's harness only if it started this close to the run's started_at. */
    static final Duration STARTED_BEFORE = Duration.ofSeconds(5);
    static final Duration STARTED_AFTER = Duration.ofSeconds(60);

    private final RunRepository runs;
    private final RunsProperties props;

    public RunReaper(RunRepository runs, RunsProperties props) {
        this.runs = runs;
        this.props = props;
    }

    /** Fails every running run whose lease has expired. */
    public void reapExpired() {
        for (Run r : runs.findExpiredLeases(OffsetDateTime.now())) {
            if (runs.finish(r.id(), RunStatus.FAILED, "lease_expired", r.costUsd(), 0, 0, 0, null)) {
                log.warn("run {} ({}, task {}) lost its lease at {}; failed it", r.id(), r.kind().dbValue(),
                        r.taskId(), r.leaseExpiresAt());
            }
        }
    }

    /** Fails every running run another instance started, killing its process if it is still alive. */
    public void failOrphans() {
        for (Run r : runs.findRunningNotOwnedBy(props.workerId())) {
            if (r.pid() != null) killIfItsHarness(r);
            if (runs.finish(r.id(), RunStatus.FAILED, "coordinator_restart", r.costUsd(), 0, 0, 0, null)) {
                log.warn("run {} ({}, task {}) was left running by {}; failed it", r.id(), r.kind().dbValue(),
                        r.taskId(), r.workerId());
            }
        }
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
        if (started.get().isBefore(runStart.minus(STARTED_BEFORE)) || started.get().isAfter(runStart.plus(STARTED_AFTER))) {
            log.info("run {}: pid {} started at {}, not with the run ({}); not killing it", r.id(), r.pid(),
                    started.get(), runStart);
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
