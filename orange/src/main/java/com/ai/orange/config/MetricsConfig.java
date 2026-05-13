package com.ai.orange.config;

import com.ai.orange.devenv.DevEnvRepository;
import com.ai.orange.devenv.DevEnvStatus;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Custom Prometheus gauges exposed via {@code /actuator/prometheus}: queue
 * depths, dev_env occupancy, in-flight workers by role. Each gauge is a
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

    public MetricsConfig(MeterRegistry registry,
                         TaskRepository tasks,
                         DevEnvRepository devEnvs) {
        this.registry = registry;
        this.tasks = tasks;
        this.devEnvs = devEnvs;
    }

    @PostConstruct
    void register() {
        // Queue depths — one gauge per status the operator cares about.
        Gauge.builder("orange.tasks.ready", tasks, t -> t.findRecent(TaskStatus.READY, 1000).size())
                .description("Tasks in READY status awaiting a worker")
                .register(registry);
        Gauge.builder("orange.tasks.in_progress",
                        tasks, t -> t.findInProgress(null).size())
                .description("Tasks currently being worked on")
                .register(registry);
        Gauge.builder("orange.tasks.pr_open", tasks, t -> t.findRecent(TaskStatus.PR_OPEN, 1000).size())
                .description("Tasks with an open PR awaiting merge")
                .register(registry);

        // In-progress count per role — labelled so the operator can spot
        // tester-saturated vs dev-saturated states.
        for (String role : new String[]{"dev", "tester", "reviewer", "planner", "triage"}) {
            Gauge.builder("orange.tasks.in_progress.by_role",
                            tasks, t -> t.countInProgress(role))
                    .tag("role", role)
                    .description("Tasks in_progress for a specific role")
                    .register(registry);
        }

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
