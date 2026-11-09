package com.ai.orange.task;

import java.util.Arrays;
import java.util.Set;

/**
 * Task lifecycle for evidence-gated integration (project-space spec §10):
 * <pre>
 * pending ─deps integrated─▶ ready ─slot─▶ running ─run done─▶ verifying ─evidence ok─▶ verified ─integrate─▶ integrated
 *                                            ▲                     │ gaps                  │ │ conflict
 *                                            └──── fix feedback ───┴───────────────────────┘ │
 *                                                       verifying ◀── rebase changed head ───┘
 * any non-terminal ─▶ needs_human ─answer─▶ ready · cancelled · failed
 * </pre>
 * "Verified" means orange ran the evidence itself; nothing an agent says moves a task past
 * {@code verifying}.
 */
public enum TaskStatus {
    PENDING("pending"),
    READY("ready"),
    RUNNING("running"),
    VERIFYING("verifying"),
    VERIFIED("verified"),
    INTEGRATED("integrated"),
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
        return this == INTEGRATED || this == FAILED || this == CANCELLED;
    }

    public Set<TaskStatus> allowedNext() {
        return switch (this) {
            case PENDING -> Set.of(READY, NEEDS_HUMAN, CANCELLED, FAILED);
            case READY -> Set.of(RUNNING, NEEDS_HUMAN, CANCELLED, FAILED);
            case RUNNING -> Set.of(VERIFYING, NEEDS_HUMAN, CANCELLED, FAILED);
            // RUNNING from VERIFYING: the evidence or the diff review found gaps to fix.
            case VERIFYING -> Set.of(VERIFIED, RUNNING, NEEDS_HUMAN, CANCELLED, FAILED);
            // VERIFYING from VERIFIED: rebasing onto a moved base changed the head, so re-verify.
            // RUNNING from VERIFIED: the rebase conflicted, so a fix run resolves it.
            case VERIFIED -> Set.of(INTEGRATED, VERIFYING, RUNNING, NEEDS_HUMAN, CANCELLED, FAILED);
            case NEEDS_HUMAN -> Set.of(READY, CANCELLED, FAILED);
            // Operator reopen. CANCELLED stays terminal: a cancel was an explicit user decision.
            case FAILED -> Set.of(READY);
            case INTEGRATED, CANCELLED -> Set.of();
        };
    }
}
