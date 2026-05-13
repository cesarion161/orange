package com.ai.orange.workflow;

import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;

@WorkflowImpl(taskQueues = PipelineWorkflow.TASK_QUEUE)
public class PipelineWorkflowImpl implements PipelineWorkflow {

    private static final Logger log = Workflow.getLogger(PipelineWorkflowImpl.class);

    /** Hard cap on the dev↔qa loop. Keeps a tester that always fails from spinning forever. */
    private static final int MAX_STAGE_ATTEMPTS = 8;

    /** Short, retryable activities (claim, mark*, cleanup). */
    private final PipelineActivities shortActivities = Workflow.newActivityStub(
            PipelineActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(60))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(3).build())
                    .build());

    /**
     * Long-running runStage gets its own stub: 2-hour cap, no retries. Stuck
     * subprocesses are caught by the DB-level heartbeat reaper, not Temporal.
     */
    private final PipelineActivities runActivities = Workflow.newActivityStub(
            PipelineActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofHours(2))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    /**
     * acquireDevEnv blocks on the pool — its timeout matches the longest a
     * test stage might reasonably wait for an env to free up. Set on the
     * activity stub rather than the workflow so each call gets its own clock.
     */
    private final PipelineActivities envActivities = Workflow.newActivityStub(
            PipelineActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(35))
                    .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
                    .build());

    @Override
    public void execute(UUID taskId, Pipeline pipeline) {
        String workerId = "wf-" + Workflow.getInfo().getWorkflowId();
        log.info("PipelineWorkflow start taskId={} pipeline={} workerId={}",
                taskId, pipeline.name(), workerId);

        if (!shortActivities.claim(taskId, workerId)) {
            log.info("task {} not claimable; exiting", taskId);
            return;
        }

        String worktreePath;
        try {
            worktreePath = shortActivities.prepareWorktree(taskId, "main");
        } catch (Exception e) {
            log.error("prepareWorktree failed for {}: {}", taskId, e.getMessage());
            shortActivities.markFailed(taskId, "worktree-setup: " + e.getMessage());
            return;
        }

        try {
            iterateStages(taskId, pipeline, worktreePath);
        } finally {
            shortActivities.cleanupWorktree(taskId);
        }
    }

    private void iterateStages(UUID taskId, Pipeline pipeline, String worktreePath) {
        String augmentation = null;
        int idx = 0;
        int totalAttempts = 0;

        while (idx < pipeline.stages().size()) {
            if (++totalAttempts > MAX_STAGE_ATTEMPTS) {
                log.error("task {} exceeded {} total stage attempts; failing", taskId, MAX_STAGE_ATTEMPTS);
                shortActivities.markFailed(taskId, "too many stage attempts");
                return;
            }

            Stage stage = pipeline.stages().get(idx);
            log.info("task {} → stage {} (role={}, attempt {}, requiresEnv={})",
                    taskId, stage.name(), stage.role(), totalAttempts, stage.requiresEnv());

            EnvInfo env = null;
            if (stage.requiresEnv()) {
                try {
                    env = envActivities.acquireDevEnv(taskId);
                } catch (Exception e) {
                    log.error("acquireDevEnv failed for task {} stage {}: {}",
                            taskId, stage.name(), e.getMessage());
                    shortActivities.markFailed(taskId,
                            "stage " + stage.name() + " env-acquire: " + e.getMessage());
                    return;
                }
            }

            StageOutcome outcome;
            try {
                outcome = runActivities.runStage(taskId, stage.role(), worktreePath, augmentation, env);
            } catch (Exception e) {
                log.error("runStage threw for task {} stage {}: {}", taskId, stage.name(), e.getMessage());
                shortActivities.markFailed(taskId, "stage " + stage.name() + ": " + e.getMessage());
                return;
            } finally {
                if (env != null) {
                    try {
                        shortActivities.releaseDevEnv(env);
                    } catch (Exception e) {
                        log.warn("releaseDevEnv after stage {} for task {} failed: {}",
                                stage.name(), taskId, e.getMessage());
                    }
                }
            }

            if (outcome.succeeded()) {
                augmentation = null;       // clear; the next stage starts fresh
                idx++;
                continue;
            }

            if (stage.onFailure() == OnFailure.RETRY_PREVIOUS && idx > 0) {
                augmentation = outcome.report();
                log.info("task {} stage {} failed; retrying previous stage with augmentation",
                        taskId, stage.name());
                idx--;
                continue;
            }

            shortActivities.markFailed(taskId,
                    "stage " + stage.name() + " failed (status=" + outcome.agentStatus()
                            + " verdict=" + outcome.verdict() + ")");
            return;
        }

        try {
            String prUrl = shortActivities.openPr(taskId, worktreePath);
            log.info("task {} → PR opened at {}", taskId, prUrl);
        } catch (Exception e) {
            log.error("openPr failed for task {}: {}", taskId, e.getMessage());
            shortActivities.markFailed(taskId, "open-pr: " + e.getMessage());
            return;
        }
        shortActivities.markPrOpen(taskId);
    }
}
