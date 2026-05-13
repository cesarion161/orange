package com.ai.orange.api;

import com.ai.orange.devenv.DevEnv;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.UUID;

public record DevEnvResponse(
        UUID id,
        String name,
        String status,
        Object ports,
        String leasedBy,
        OffsetDateTime leasedAt,
        OffsetDateTime releasedAt,
        Object metadata) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static DevEnvResponse of(DevEnv e) {
        return new DevEnvResponse(
                e.id(),
                e.name(),
                e.status().dbValue(),
                parse(e.ports() == null ? null : e.ports().data()),
                e.leasedBy(),
                e.leasedAt(),
                e.releasedAt(),
                parse(e.metadata() == null ? null : e.metadata().data()));
    }

    /**
     * Parse JSONB into a plain {@code Map}/{@code List}/scalar tree. We avoid
     * Jackson's {@link com.fasterxml.jackson.databind.JsonNode} here: Spring
     * MVC's default ObjectMapper sometimes serializes it as a bean (every
     * {@code isArray()}, {@code isObject()} getter appears as a field), which
     * produces useless API output. Returning the parsed plain Java tree dodges
     * that entirely.
     */
    private static Object parse(String json) {
        if (json == null) return null;
        try {
            return MAPPER.readValue(json, Object.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
