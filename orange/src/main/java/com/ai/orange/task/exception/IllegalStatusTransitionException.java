package com.ai.orange.task.exception;

import com.ai.orange.task.TaskStatus;
import java.util.UUID;

public class IllegalStatusTransitionException extends RuntimeException {
    public IllegalStatusTransitionException(UUID taskId, TaskStatus from, TaskStatus to) {
        super("Task " + taskId + ": " + from + " → " + to + " is not an allowed transition");
    }
}
