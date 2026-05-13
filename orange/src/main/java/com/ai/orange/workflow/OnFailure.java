package com.ai.orange.workflow;

/**
 * What the pipeline workflow should do when a stage reports failure.
 *
 * <ul>
 *   <li>{@link #FAIL_TASK} — give up, transition the task to {@code failed}.</li>
 *   <li>{@link #RETRY_PREVIOUS} — go back one stage, augmenting that stage's
 *       prompt with the failing stage's report. Used for tester → dev loops.</li>
 * </ul>
 */
public enum OnFailure {
    FAIL_TASK,
    RETRY_PREVIOUS
}
