package com.ai.orange.run;

import com.ai.orange.listener.PgNotificationEvent;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Starts queued runs: per kind up to its slots, and in all up to {@code max-concurrent}. Each
 * claimed run executes on its own virtual thread. The counts are this instance's own; plan runs
 * started directly ({@link WorkerRunner#runDirect}) take no slot.
 */
@Component
public class RunScheduler {

    private static final Logger log = LoggerFactory.getLogger(RunScheduler.class);

    private final RunRepository runs;
    private final RunExecutors executors;
    private final RunsProperties props;
    private final SchedulerPause pause;
    private final Map<RunKind, AtomicInteger> inFlight = new ConcurrentHashMap<>();
    private final AtomicInteger total = new AtomicInteger();

    public RunScheduler(RunRepository runs, RunExecutors executors, RunsProperties props, SchedulerPause pause) {
        this.runs = runs;
        this.executors = executors;
        this.props = props;
        this.pause = pause;
    }

    /** Claims and starts queued runs until every kind's slots, or the global cap, are full. */
    public synchronized void fillSlots() {
        if (pause.current().isPresent()) return;
        for (RunKind kind : RunKind.values()) {
            int slots = props.slots(kind);
            if (slots <= 0) continue;
            Optional<RunExecutor> executor = executors.forKind(kind);
            if (executor.isEmpty()) continue;
            while (counter(kind).get() < slots && total.get() < props.maxConcurrent()) {
                Optional<Run> run = runs.claimNext(kind, props.workerId(), props.leaseTtl());
                if (run.isEmpty()) break;
                start(executor.get(), run.get());
            }
        }
    }

    /** Runs of {@code kind} this instance is executing now. */
    public int inFlight(RunKind kind) {
        return counter(kind).get();
    }

    private void start(RunExecutor executor, Run run) {
        counter(run.kind()).incrementAndGet();
        total.incrementAndGet();
        log.info("starting {} run {} for task {}", run.kind().dbValue(), run.id(), run.taskId());
        Thread.ofVirtual().name("run-" + run.kind().dbValue() + "-" + run.id()).start(() -> {
            try {
                executor.execute(run);
            } catch (Throwable t) {
                // An executor should never throw; if one does, the run would otherwise sit running until
                // its lease expired, and the same failure would recur at once, so the scheduler pauses.
                log.error("{} run {} failed in its executor: {}", run.kind().dbValue(), run.id(), t.toString(), t);
                try {
                    if (runs.finish(run.id(), RunStatus.FAILED, "start_failed", BigDecimal.ZERO, 0, 0, 0, null)) {
                        pause.pause("start_failed", null);
                    }
                } catch (RuntimeException e) {
                    log.warn("could not mark run {} failed: {}", run.id(), e.getMessage());
                }
            } finally {
                counter(run.kind()).decrementAndGet();
                total.decrementAndGet();
                if (props.loopEnabled()) wake();
            }
        });
    }

    @Scheduled(fixedDelayString = "${orange.runs.scheduler-poll:5s}")
    void poll() {
        if (props.loopEnabled()) fillSafely();
    }

    @EventListener
    void onNotification(PgNotificationEvent event) {
        if (props.loopEnabled() && "run_queued".equals(event.channel())) wake();
    }

    private void wake() {
        Thread.ofVirtual().name("fill-slots").start(this::fillSafely);
    }

    private void fillSafely() {
        try {
            fillSlots();
        } catch (RuntimeException e) {
            log.warn("filling run slots failed: {}", e.toString());
        }
    }

    private AtomicInteger counter(RunKind kind) {
        return inFlight.computeIfAbsent(kind, k -> new AtomicInteger());
    }
}
