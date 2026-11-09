package com.ai.orange.api;

import com.ai.orange.evidence.Evidence;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.OffsetDateTime;
import java.util.UUID;

/** One evidence row: a check orange ran on a head, or a diff review of it. */
public record EvidenceResponse(
        UUID id,
        @JsonProperty("head_sha") String headSha,
        String kind,
        String command,
        @JsonProperty("exit_code") Integer exitCode,
        @JsonProperty("output_tail") String outputTail,
        boolean passed,
        @JsonProperty("run_id") UUID runId,
        @JsonProperty("created_at") OffsetDateTime createdAt) {

    public static EvidenceResponse of(Evidence e) {
        return new EvidenceResponse(e.id(), e.headSha(), e.kind(), e.command(), e.exitCode(), e.outputTail(),
                e.passed(), e.runId(), e.createdAt());
    }
}
