package com.ai.orange.workflow;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.UUID;

/**
 * Pipeline workflow: drives a task through the ordered stages selected by its
 * {@code tasks.pipeline} value. One workflow instance per task, keyed by task
 * UUID for natural deduplication.
 */
@WorkflowInterface
public interface PipelineWorkflow {

    String TASK_QUEUE = "orange-pipelines";

    @WorkflowMethod
    void execute(UUID taskId, Pipeline pipeline);
}
