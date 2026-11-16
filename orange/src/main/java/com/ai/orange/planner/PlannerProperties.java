package com.ai.orange.planner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param outputRoot directory under which each planner run gets its own {@code <plan-id>/} subdir
 *                   (cwd of the planner agent). Kept after the run for inspection. The run's
 *                   timeout is the {@code plan} kind's, {@code orange.runs.timeouts.plan}.
 * @param askAboveTasks a plan of more tasks than this asks the user first under {@code plan-approval:
 *                      auto} (remote-first spec §7.2: a large plan is worth a look)
 */
@ConfigurationProperties(prefix = "orange.planner")
public record PlannerProperties(@DefaultValue("./.plans") String outputRoot,
                                @DefaultValue("6") int askAboveTasks) {
}
