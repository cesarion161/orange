package com.ai.orange.task.exception;

import java.util.List;

public class DagCycleException extends RuntimeException {
    private final List<?> cyclePath;

    public DagCycleException(List<?> cyclePath) {
        super("Task graph contains a cycle: " + cyclePath);
        this.cyclePath = List.copyOf(cyclePath);
    }

    public List<?> cyclePath() {
        return cyclePath;
    }
}
