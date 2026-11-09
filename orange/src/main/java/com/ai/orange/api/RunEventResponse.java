package com.ai.orange.api;

import com.ai.orange.run.RunEventRow;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;

/** One event of a run's stream ({@code init}, {@code assistant}, {@code tool_use}, …). */
public record RunEventResponse(int seq, String type, Object payload,
                               @JsonProperty("created_at") OffsetDateTime createdAt) {

    public static RunEventResponse of(RunEventRow e) {
        return new RunEventResponse(e.seq(), e.type(), RunResponse.json(e.payload()), e.createdAt());
    }
}
