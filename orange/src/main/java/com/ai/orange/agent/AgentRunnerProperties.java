package com.ai.orange.agent;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param python          absolute (or working-dir-relative) path to the Python interpreter
 *                        whose venv has the {@code agent-runner} package installed
 * @param module          Python module to run with {@code python -u -m <module>}
 * @param startupTimeout  how long to wait for the runner to emit its first {@code ready} event
 * @param cancelGrace     after sending {@code cancel}, how long to wait for clean shutdown
 *                        before SIGTERM/SIGKILL escalation
 */
@ConfigurationProperties(prefix = "orange.agent-runner")
public record AgentRunnerProperties(
        @DefaultValue("../agent-runner/.venv/bin/python") String python,
        @DefaultValue("agent_runner") String module,
        @DefaultValue("30s") Duration startupTimeout,
        @DefaultValue("10s") Duration cancelGrace) {
}
