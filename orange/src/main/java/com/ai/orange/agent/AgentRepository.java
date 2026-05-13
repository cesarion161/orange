package com.ai.orange.agent;

import static com.ai.orange.db.jooq.Tables.AGENTS;

import com.ai.orange.db.jooq.tables.records.AgentsRecord;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

@Repository
public class AgentRepository {

    private final DSLContext dsl;

    public AgentRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Optional<Agent> findByName(String name) {
        return dsl.selectFrom(AGENTS).where(AGENTS.NAME.eq(name)).fetchOptional().map(AgentRepository::toDomain);
    }

    public Optional<Agent> findById(UUID id) {
        return dsl.selectFrom(AGENTS).where(AGENTS.ID.eq(id)).fetchOptional().map(AgentRepository::toDomain);
    }

    public List<Agent> findEnabledByRole(String role) {
        return dsl.selectFrom(AGENTS)
                .where(AGENTS.ROLE.eq(role).and(AGENTS.ENABLED.eq(true)))
                .fetch()
                .map(AgentRepository::toDomain);
    }

    static Agent toDomain(AgentsRecord r) {
        return new Agent(
                r.getId(),
                r.getName(),
                r.getRole(),
                r.getSystemPrompt(),
                r.getModel(),
                r.getFallbackModel(),
                r.getAllowedTools(),
                r.getDisallowedTools(),
                r.getPermissionMode(),
                r.getMaxTurns(),
                r.getMaxBudgetUsd(),
                Boolean.TRUE.equals(r.getEnabled()),
                r.getMetadata(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}
