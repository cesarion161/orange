package com.ai.orange.planner;

import static com.ai.orange.db.jooq.Tables.PLANS;

import com.ai.orange.db.jooq.tables.records.PlansRecord;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PlanRepository {

    private final DSLContext dsl;

    public PlanRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** A new plan, {@code planning}. */
    public Plan insert(UUID projectId, String request, String note, UUID revisesId, UUID milestoneId) {
        return toDomain(dsl.insertInto(PLANS)
                .set(PLANS.PROJECT_ID, projectId)
                .set(PLANS.REQUEST, request)
                .set(PLANS.NOTE, note)
                .set(PLANS.REVISES_ID, revisesId)
                .set(PLANS.MILESTONE_ID, milestoneId)
                .set(PLANS.STATUS, Plan.PLANNING)
                .returning().fetchOne());
    }

    public Optional<Plan> findById(UUID id) {
        return dsl.selectFrom(PLANS).where(PLANS.ID.eq(id)).fetchOptional().map(PlanRepository::toDomain);
    }

    /** Reads the plan and locks its row until the caller's transaction ends, so one answer acts at a time. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Plan> lock(UUID id) {
        return dsl.selectFrom(PLANS).where(PLANS.ID.eq(id)).forUpdate().fetchOptional()
                .map(PlanRepository::toDomain);
    }

    /** Plans in any of these statuses, oldest first. */
    public List<Plan> findByStatus(Collection<String> statuses) {
        return dsl.selectFrom(PLANS).where(PLANS.STATUS.in(statuses))
                .orderBy(PLANS.CREATED_AT.asc(), PLANS.ID.asc())
                .fetch().map(PlanRepository::toDomain);
    }

    /** A project's latest plans, newest first. */
    public List<Plan> findRecent(UUID projectId, int limit) {
        return dsl.selectFrom(PLANS).where(PLANS.PROJECT_ID.eq(projectId))
                .orderBy(PLANS.CREATED_AT.desc(), PLANS.ID.desc())
                .limit(Math.clamp(limit, 1, 200))
                .fetch().map(PlanRepository::toDomain);
    }

    /** Records what planning produced and the status it leads to, in one write. */
    public void finishPlanning(UUID id, String status, Boolean askFirst, String reason, JSONB plan,
                               String architecture, String failure, UUID runId, UUID milestoneId) {
        dsl.update(PLANS)
                .set(PLANS.STATUS, status)
                .set(PLANS.ASK_FIRST, askFirst)
                .set(PLANS.REASON, reason)
                .set(PLANS.PLAN, plan)
                .set(PLANS.ARCHITECTURE, architecture)
                .set(PLANS.FAILURE, failure)
                .set(PLANS.RUN_ID, runId)
                .set(PLANS.MILESTONE_ID, milestoneId)
                .where(PLANS.ID.eq(id))
                .execute();
    }

    /** Moves the plan from {@code from} to {@code to}; false when it was no longer {@code from}. */
    public boolean move(UUID id, String from, String to) {
        return dsl.update(PLANS).set(PLANS.STATUS, to)
                .where(PLANS.ID.eq(id).and(PLANS.STATUS.eq(from)))
                .execute() > 0;
    }

    public void setAttention(UUID id, UUID attentionId) {
        dsl.update(PLANS).set(PLANS.ATTENTION_ID, attentionId).where(PLANS.ID.eq(id)).execute();
    }

    static Plan toDomain(PlansRecord r) {
        return new Plan(r.getId(), r.getProjectId(), r.getRequest(), r.getNote(), r.getRevisesId(), r.getStatus(),
                r.getAskFirst(), r.getReason(), r.getPlan(), r.getArchitecture(), r.getFailure(),
                r.getMilestoneId(), r.getRunId(), r.getAttentionId(), r.getCreatedAt(), r.getUpdatedAt());
    }
}
