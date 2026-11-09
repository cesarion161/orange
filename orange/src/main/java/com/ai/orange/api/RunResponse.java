package com.ai.orange.api;

import com.ai.orange.run.Run;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

/** A run as the operator API shows it. Never carries the run's MCP token hash. */
public record RunResponse(
        UUID id,
        @JsonProperty("task_id") UUID taskId,
        String kind,
        String status,
        String role,
        int attempt,
        String tier,
        String model,
        String effort,
        String auth,
        @JsonProperty("cost_usd") BigDecimal costUsd,
        String error,
        @JsonProperty("queued_at") OffsetDateTime queuedAt,
        @JsonProperty("started_at") OffsetDateTime startedAt,
        @JsonProperty("finished_at") OffsetDateTime finishedAt,
        Object input,
        Object outcome) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static RunResponse of(Run r) {
        return new RunResponse(r.id(), r.taskId(), r.kind().dbValue(), r.status().dbValue(), r.role(), r.attempt(),
                r.tier(), r.model(), r.effort(), r.auth(), r.costUsd(), r.error(), r.queuedAt(), r.startedAt(),
                r.finishedAt(), json(r.input()), json(r.outcome()));
    }

    static Object json(JSONB value) {
        if (value == null) return null;
        try {
            return MAPPER.readValue(value.data(), Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
