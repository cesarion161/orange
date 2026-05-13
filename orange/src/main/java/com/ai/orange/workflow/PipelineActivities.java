package com.ai.orange.workflow;

import io.temporal.activity.ActivityInterface;
import io.temporal.activity.ActivityMethod;
import java.util.UUID;

/**
 * Activities consumed by {@link PipelineWorkflow}. Methods are intended to be
 * Temporal-activity-safe: short-running OR explicitly long-tail
 * ({@link #runStage}).
 */
@ActivityInterface
public interface PipelineActivities {

    /**
     * Idempotent: transitions {@code READY} → {@code IN_PROGRESS} or no-ops if
     * we already own the task. Returns {@code true} iff the task is now ours.
     */
    @ActivityMethod
    boolean claim(UUID taskId, String workerId);

    @ActivityMethod
    String prepareWorktree(UUID taskId, String baseRef);

    /**
     * Lease a {@code dev_env} for {@code taskId}. Blocks up to
     * {@code orange.envs.acquire-timeout} (default 30 min) on the pool. Used
     * for stages marked {@code requiresEnv=true} (typically tester stages) so
     * tester parallelism is naturally capped by the env pool size.
     */
    @ActivityMethod
    EnvInfo acquireDevEnv(UUID taskId);

    /**
     * Return a leased env to the pool. Tears down the env's compose stack
     * first if {@code orange.envs.compose-file} is configured. Idempotent.
     */
    @ActivityMethod
    void releaseDevEnv(EnvInfo env);

    /**
     * Runs the agent associated with {@code agentRole} against the worktree.
     * The agent's prompt is the task description optionally augmented with
     * {@code augmentation} (the previous stage's failure report on a retry)
     * AND, when {@code env} is non-null, a leased dev_env block surfacing
     * URLs/ports/data-dir so the agent doesn't need to allocate ports itself.
     *
     * For tester-role stages, post-run we read {@code report.md} from the
     * worktree (looked up via the agent's {@code final.artifacts.report}, or
     * defaulted to {@code report.md}) and parse the verdict from its first line.
     */
    @ActivityMethod
    StageOutcome runStage(UUID taskId, String agentRole, String worktreePath,
                          String augmentation, EnvInfo env);

    /**
     * Push the worktree's branch and open a GitHub PR for {@code taskId}.
     * Stamps {@code pr_number}, {@code pr_url}, {@code pr_repo} into
     * {@code tasks.metadata}. Returns the PR URL for logging; the workflow
     * cares mostly about the side effect on the task row.
     */
    @ActivityMethod
    String openPr(UUID taskId, String worktreePath);

    @ActivityMethod
    void markPrOpen(UUID taskId);

    @ActivityMethod
    void markDevReady(UUID taskId);

    @ActivityMethod
    void markFailed(UUID taskId, String reason);

    @ActivityMethod
    void cleanupWorktree(UUID taskId);
}
