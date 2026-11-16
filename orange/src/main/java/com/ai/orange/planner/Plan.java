package com.ai.orange.planner;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

/**
 * A plan (remote-first spec §7.2): the user's request for a project, what the planner made of it
 * and what orange decided. It is planned in the background, then dispatched at once or held for the
 * user's answer to one attention item.
 *
 * @param note        the user's revision note when this plan revises {@code revisesId}
 * @param askFirst    orange's decision (null while planning)
 * @param reason      why it asks, one line for the user
 * @param plan        the planner's {@code plan.json}
 * @param failure     why the planner did not deliver
 * @param milestoneId the milestone its tasks serve
 * @param attentionId the {@code plan_approval} item while it asks
 */
public record Plan(UUID id, UUID projectId, String request, String note, UUID revisesId, String status,
                   Boolean askFirst, String reason, JSONB plan, String architecture, String failure,
                   UUID milestoneId, UUID runId, UUID attentionId, OffsetDateTime createdAt,
                   OffsetDateTime updatedAt) {

    public static final String PLANNING = "planning";
    public static final String ASKING = "asking";
    public static final String DISPATCHED = "dispatched";
    public static final String DROPPED = "dropped";
    public static final String FAILED = "failed";
}
