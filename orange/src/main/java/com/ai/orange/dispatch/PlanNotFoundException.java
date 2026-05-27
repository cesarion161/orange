package com.ai.orange.dispatch;

import java.util.UUID;

/** Thrown when {@code POST /dispatch} references a planId with no saved plan.json on disk. */
public class PlanNotFoundException extends RuntimeException {
    public PlanNotFoundException(UUID planId) {
        super("no saved plan for planId " + planId);
    }
}
