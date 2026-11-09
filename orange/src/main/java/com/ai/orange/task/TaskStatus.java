package com.ai.orange.task;

import java.util.Arrays;
import java.util.Set;

/**
 * Task lifecycle (spec §6):
 * <pre>
 * pending ──deps merged──▶ ready ──implement run queued──▶ running ──PR pushed──▶ in_review ──merged──▶ merged
 *                                                             ▲   │                   │
 *                                    feedback (CI, comments)  └───┼───────────────────┤ closed unmerged,
 *                                                                 ▼                   ▼ repeated failures
 *                                                             needs_human ──answer──▶ ready
 * any non-terminal ──▶ cancelled · failed
 * </pre>
 */
public enum TaskStatus {
    PENDING("pending"),
    READY("ready"),
    RUNNING("running"),
    IN_REVIEW("in_review"),
    MERGED("merged"),
    NEEDS_HUMAN("needs_human"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String dbValue;

    TaskStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static TaskStatus fromDb(String value) {
        return Arrays.stream(values())
                .filter(s -> s.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown task status: " + value));
    }

    /** Nothing happens to a terminal task on its own; FAILED can still be reopened by an operator. */
    public boolean isTerminal() {
        return this == MERGED || this == FAILED || this == CANCELLED;
    }

    public Set<TaskStatus> allowedNext() {
        return switch (this) {
            case PENDING -> Set.of(READY, CANCELLED, FAILED);
            case READY -> Set.of(RUNNING, CANCELLED, FAILED);
            // MERGED from RUNNING: a person merged the PR while a fix run was still going.
            case RUNNING -> Set.of(IN_REVIEW, NEEDS_HUMAN, MERGED, CANCELLED, FAILED);
            // RUNNING from IN_REVIEW: feedback (CI, review comments, conflicts) asks for rework.
            case IN_REVIEW -> Set.of(MERGED, RUNNING, NEEDS_HUMAN, CANCELLED, FAILED);
            case NEEDS_HUMAN -> Set.of(READY, MERGED, CANCELLED, FAILED);
            // Operator reopen. CANCELLED stays terminal: a cancel was an explicit user decision.
            case FAILED -> Set.of(READY);
            case MERGED, CANCELLED -> Set.of();
        };
    }
}
