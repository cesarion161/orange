package com.ai.orange.workflow;

import com.ai.orange.listener.PgNotificationEvent;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskStatus;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Bridges {@code LISTEN task_ready} into Temporal {@link PipelineWorkflow}
 * starts. Each ready task gets at most one workflow execution (deduplicated by
 * deterministic workflow id {@code task-<uuid>}).
 *
 * Also drains any tasks already in {@code READY} state on application start
 * so we recover from orchestrator downtime gracefully.
 *
 * Disable in tests with {@code orange.workflow.auto-start=false} so tests can
 * drive activities directly without the auto-starter racing them.
 */
@Service
@ConditionalOnProperty(name = "orange.workflow.auto-start", havingValue = "true", matchIfMissing = true)
public class WorkflowStarter {

    private static final Logger log = LoggerFactory.getLogger(WorkflowStarter.class);

    private final WorkflowClient workflowClient;
    private final TaskRepository tasks;
    private final PipelineRegistry pipelines;

    public WorkflowStarter(WorkflowClient workflowClient,
                           TaskRepository tasks,
                           PipelineRegistry pipelines) {
        this.workflowClient = workflowClient;
        this.tasks = tasks;
        this.pipelines = pipelines;
    }

    @EventListener
    public void onPgNotification(PgNotificationEvent event) {
        if (!"task_ready".equals(event.channel())) return;
        String payload = event.payload();
        if (payload == null) return;
        // Format from V1__init_tasks.sql notify_task_ready trigger: "<uuid>:<status>"
        String[] parts = payload.split(":", 2);
        if (parts.length < 2) return;
        if (!"ready".equals(parts[1])) return;          // dev_ready handled in later phases
        UUID taskId;
        try {
            taskId = UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            log.warn("ignored malformed task_ready payload: {}", payload);
            return;
        }
        startForTask(taskId);
    }

    @EventListener(ApplicationReadyEvent.class)
    void drainExistingReadyTasks() {
        var ready = tasks.findReady();
        log.info("draining {} ready tasks on startup", ready.size());
        for (Task t : ready) startForTask(t.id());
    }

    private void startForTask(UUID taskId) {
        Task task = tasks.findById(taskId).orElse(null);
        if (task == null) return;
        if (task.status() != TaskStatus.READY) return;

        Pipeline pipeline = pipelines.get(task.pipeline()).orElse(null);
        if (pipeline == null) {
            log.warn("task {} pipeline={} unknown; skipping", taskId, task.pipeline());
            return;
        }

        WorkflowOptions opts = WorkflowOptions.newBuilder()
                .setTaskQueue(PipelineWorkflow.TASK_QUEUE)
                .setWorkflowId("task-" + taskId)
                .build();
        PipelineWorkflow stub = workflowClient.newWorkflowStub(PipelineWorkflow.class, opts);
        try {
            WorkflowClient.start(stub::execute, taskId, pipeline);
            log.info("started PipelineWorkflow taskId={} pipeline={}", taskId, pipeline.name());
        } catch (WorkflowExecutionAlreadyStarted ignored) {
            // Idempotent: another orchestrator instance got to it first.
        } catch (Exception e) {
            log.error("could not start workflow for task {}: {}", taskId, e.getMessage());
        }
    }
}
