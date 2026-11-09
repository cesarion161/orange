package com.ai.orange.planner;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param outputRoot directory under which each planner run gets its own {@code <plan-id>/} subdir
 *                   (cwd of the planner agent). Kept after the run for inspection. The run's
 *                   timeout is the {@code plan} kind's, {@code orange.runs.timeouts.plan}.
 */
@ConfigurationProperties(prefix = "orange.planner")
public record PlannerProperties(@DefaultValue("./.plans") String outputRoot) {
}
