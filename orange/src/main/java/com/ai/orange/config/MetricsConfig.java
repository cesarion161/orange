package com.ai.orange.config;

import com.ai.orange.devenv.DevEnvRepository;
import com.ai.orange.devenv.DevEnvStatus;
import com.ai.orange.run.RunKind;
import com.ai.orange.run.RunRepository;
import com.ai.orange.run.RunStatus;
import com.ai.orange.run.SchedulerPause;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Custom Prometheus gauges exposed via {@code /actuator/prometheus}: tasks per
 * status, runs per kind and status, the scheduler pause, and dev_env occupancy. Each gauge is a
 * {@link Gauge} backed by a {@code Supplier<Number>} so the value is computed
 * lazily on scrape — no scheduled refresh thread, no stale cache.
 *
 * <p>Spring Boot's actuator + micrometer-registry-prometheus do the rest; we
 * just need to declare the gauges once at startup.
 */
@Component
public class MetricsConfig {

    private final MeterRegistry registry;
    private final TaskRepository tasks;
    private final DevEnvRepository devEnvs;
    private final RunRepository runs;
    private final SchedulerPause pause;

    public MetricsConfig(MeterRegistry registry,
                         TaskRepository tasks,
                         DevEnvRepository devEnvs,
                         RunRepository runs,
                         SchedulerPause pause) {
        this.registry = registry;
        this.tasks = tasks;
        this.devEnvs = devEnvs;
        this.runs = runs;
        this.pause = pause;
    }

    @PostConstruct
    void register() {
        // Queue depths — one gauge per non-terminal status, tagged by status.
        for (TaskStatus status : new TaskStatus[]{TaskStatus.READY, TaskStatus.RUNNING,
                TaskStatus.VERIFYING, TaskStatus.VERIFIED, TaskStatus.NEEDS_HUMAN}) {
            Gauge.builder("orange.tasks", tasks, t -> t.countByStatus(status))
                    .tag("status", status.dbValue())
                    .description("Tasks currently in this status")
                    .register(registry);
        }

        // Runs queued and running, per kind.
        for (RunKind kind : RunKind.values()) {
            for (RunStatus status : new RunStatus[]{RunStatus.QUEUED, RunStatus.RUNNING}) {
                Gauge.builder("orange.runs", runs, r -> r.countByStatus(kind, status))
                        .tag("kind", kind.dbValue())
                        .tag("status", status.dbValue())
                        .description("Runs currently in this status")
                        .register(registry);
            }
        }
        Gauge.builder("orange.scheduler.paused", pause, p -> p.current().isPresent() ? 1 : 0)
                .description("1 while the scheduler is paused")
                .register(registry);

        // Dev-env occupancy.
        Gauge.builder("orange.dev_envs.total", devEnvs, d -> d.findAll().size())
                .description("Total dev environments")
                .register(registry);
        Gauge.builder("orange.dev_envs.leased",
                        devEnvs, d -> d.countByStatus(DevEnvStatus.LEASED))
                .description("Currently-leased dev environments")
                .register(registry);
        Gauge.builder("orange.dev_envs.free",
                        devEnvs, d -> d.countByStatus(DevEnvStatus.FREE))
                .description("Free dev environments")
                .register(registry);
    }
}
