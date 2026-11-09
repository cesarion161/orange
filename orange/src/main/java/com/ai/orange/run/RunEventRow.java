package com.ai.orange.run;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.jooq.JSONB;

/** One normalized harness event of a run, in the order the run produced it ({@code seq}). */
public record RunEventRow(long id, UUID runId, int seq, String type, JSONB payload, OffsetDateTime createdAt) {
}
