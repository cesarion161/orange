package com.ai.orange.harness;

import java.util.Map;

/**
 * Says that a run's process lives in a container, which holds the run's working directory
 * (read-only or not), its Claude config directory and its MCP config, and nothing else of the
 * host. A {@link RunSpec} without one is a child process of orange.
 *
 * @param readOnly whether the working directory is mounted read-only (a review reads, it does not write)
 * @param labels   the container's labels: how orange finds a run's containers again
 */
public record Sandbox(boolean readOnly, Map<String, String> labels) {

    public Sandbox {
        labels = labels == null ? Map.of() : Map.copyOf(labels);
    }
}
