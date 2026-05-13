package com.ai.orange.agent;

import static com.ai.orange.db.jooq.Tables.AGENT_RUNS;

import com.ai.orange.db.jooq.tables.records.AgentRunsRecord;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRunRepository {

    private final DSLContext dsl;

    public AgentRunRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public AgentRun start(UUID taskId, UUID agentId, int attempt, String prompt, JSONB metadata) {
        AgentRunsRecord rec = dsl.insertInto(AGENT_RUNS)
                .set(AGENT_RUNS.TASK_ID, taskId)
                .set(AGENT_RUNS.AGENT_ID, agentId)
                .set(AGENT_RUNS.ATTEMPT, attempt)
                .set(AGENT_RUNS.PROMPT, prompt)
                .set(AGENT_RUNS.METADATA, metadata == null ? JSONB.valueOf("{}") : metadata)
                // status defaults to 'running'
                .returning()
                .fetchOne();
        return toDomain(rec);
    }

    public int nextAttempt(UUID taskId) {
        Integer max = dsl.select(org.jooq.impl.DSL.max(AGENT_RUNS.ATTEMPT))
                .from(AGENT_RUNS)
                .where(AGENT_RUNS.TASK_ID.eq(taskId))
                .fetchOneInto(Integer.class);
        return (max == null ? 0 : max) + 1;
    }

    public boolean finish(UUID id, AgentRunStatus status, BigDecimal costUsd, long tokensIn, long tokensOut) {
        return dsl.update(AGENT_RUNS)
                .set(AGENT_RUNS.STATUS, status.dbValue())
                .set(AGENT_RUNS.COST_USD, costUsd)
                .set(AGENT_RUNS.TOKENS_IN, tokensIn)
                .set(AGENT_RUNS.TOKENS_OUT, tokensOut)
                .set(AGENT_RUNS.FINISHED_AT, OffsetDateTime.now())
                .where(AGENT_RUNS.ID.eq(id))
                .execute() > 0;
    }

    public Optional<AgentRun> findById(UUID id) {
        return dsl.selectFrom(AGENT_RUNS).where(AGENT_RUNS.ID.eq(id)).fetchOptional().map(AgentRunRepository::toDomain);
    }

    /** Most-recent running attempt for {@code taskId}, if any. */
    public Optional<AgentRun> findActiveByTask(UUID taskId) {
        return dsl.selectFrom(AGENT_RUNS)
                .where(AGENT_RUNS.TASK_ID.eq(taskId)
                        .and(AGENT_RUNS.STATUS.eq(AgentRunStatus.RUNNING.dbValue())))
                .orderBy(AGENT_RUNS.ATTEMPT.desc())
                .limit(1)
                .fetchOptional()
                .map(AgentRunRepository::toDomain);
    }

    static AgentRun toDomain(AgentRunsRecord r) {
        return new AgentRun(
                r.getId(),
                r.getTaskId(),
                r.getAgentId(),
                r.getAttempt(),
                r.getPrompt(),
                AgentRunStatus.fromDb(r.getStatus()),
                r.getCostUsd(),
                r.getTokensIn() == null ? 0L : r.getTokensIn(),
                r.getTokensOut() == null ? 0L : r.getTokensOut(),
                r.getStartedAt(),
                r.getFinishedAt(),
                r.getMetadata());
    }
}
