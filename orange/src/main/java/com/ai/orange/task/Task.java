package com.ai.orange.task;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

/**
 * One unit of work in the DAG.
 *
 * @param role     {@code implementer} or {@code chore} (chores route to a smaller model)
 * @param failures model failures since the last human answer; drives escalation and needs_human
 * @param repo     {@code owner/name} of the remote, when known
 * @param branch   {@code orange/<task id>} once a run has committed work
 * @param headSha  the branch head being verified or integrated
 * @param prNumber PR integration mode only (a later phase)
 * @param prUrl    PR integration mode only (a later phase)
 * @param projectId the project the task belongs to; null for a task of the legacy base repo
 * @param repoId    the project's repo the work is done in; null with {@code projectId}
 * @param planId    the plan that made it; null for a task created by hand or before plans were rows
 * @param milestoneId the milestone item it was planned for; readers follow its supersede chain
 */
public record Task(
        UUID id,
        String title,
        String description,
        String role,
        TaskStatus status,
        int priority,
        int failures,
        String repo,
        String branch,
        Integer prNumber,
        String prUrl,
        String headSha,
        JSONB metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID projectId,
        UUID repoId,
        UUID planId,
        UUID milestoneId) {
}
