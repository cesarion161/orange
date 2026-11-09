package com.ai.orange.task;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

/**
 * One unit of work in the DAG.
 *
 * @param role         {@code implementer} or {@code chore} (chores route to a smaller model)
 * @param failures     model failures since the last human answer; drives escalation and needs_human
 * @param repo         {@code owner/name} of the PR's repository, once a PR exists
 * @param branch       {@code orange/<task id>} once the coordinator has pushed it
 * @param headSha      PR head the coordinator last saw
 * @param reviewedSha  head the latest review run judged
 * @param reviewVerdict {@code approve} or {@code request_changes} for {@code reviewedSha}
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
        String reviewedSha,
        String reviewVerdict,
        JSONB metadata,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
