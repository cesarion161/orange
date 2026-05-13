package com.ai.orange.planner;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param outputRoot   directory under which each planner run gets its own
 *                     {@code <plan-id>/} subdir (cwd of the planner agent).
 *                     Kept after the run for inspection.
 * @param runTimeout   how long to wait for the planner agent to emit its
 *                     {@code final} event before giving up.
 */
@ConfigurationProperties(prefix = "orange.planner")
public record PlannerProperties(
        @DefaultValue("./.plans") String outputRoot,
        @DefaultValue("10m") Duration runTimeout) {
}
