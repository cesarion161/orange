package com.ai.orange.workflow;

import java.util.List;

/**
 * Ordered list of stages that {@link PipelineWorkflow} iterates over. Stored
 * by {@code name} matching the {@code tasks.pipeline} column.
 */
public record Pipeline(String name, List<Stage> stages) {

    public Pipeline {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name required");
        if (stages == null || stages.isEmpty()) throw new IllegalArgumentException("at least one stage required");
        stages = List.copyOf(stages);
    }
}
