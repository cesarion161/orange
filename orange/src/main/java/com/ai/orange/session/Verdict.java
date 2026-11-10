package com.ai.orange.session;

import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;

/** What orange decided at one stop of a session, and whether the stop hook took it. */
public record Verdict(UUID id, UUID sessionId, UUID runId, String stopEventId, String kind, String message,
                      String rationale, Double confidence, String status, OffsetDateTime createdAt) {

    public static final String CONTINUE = "continue";
    public static final String VERIFY = "verify";
    public static final String ESCALATE = "escalate";
    public static final String IDLE = "idle";
    public static final Set<String> KINDS = Set.of(CONTINUE, VERIFY, ESCALATE, IDLE);

    public static final String PENDING = "pending";
    public static final String DELIVERED = "delivered";
    public static final String CANCELLED = "cancelled";
    public static final String EXPIRED = "expired";
}
