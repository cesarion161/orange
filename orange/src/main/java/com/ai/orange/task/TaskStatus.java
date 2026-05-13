package com.ai.orange.task;

import java.util.Arrays;
import java.util.Set;

public enum TaskStatus {
    PENDING("pending"),
    READY("ready"),
    IN_PROGRESS("in_progress"),
    PR_OPEN("pr_open"),
    DEV_READY("dev_ready"),
    IN_TEST("in_test"),
    TEST_DONE("test_done"),
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

    public boolean isTerminal() {
        return this == TEST_DONE || this == FAILED || this == CANCELLED;
    }

    public Set<TaskStatus> allowedNext() {
        return switch (this) {
            case PENDING -> Set.of(READY, CANCELLED);
            case READY -> Set.of(IN_PROGRESS, CANCELLED);
            case IN_PROGRESS -> Set.of(PR_OPEN, FAILED, CANCELLED, READY);
            case PR_OPEN -> Set.of(DEV_READY, FAILED, CANCELLED);
            case DEV_READY -> Set.of(IN_TEST, CANCELLED);
            case IN_TEST -> Set.of(TEST_DONE, READY, FAILED, CANCELLED);
            // Triage / regression-fail reopen: FAILED or TEST_DONE can be bounced back
            // to READY by the triage agent (or operator). CANCELLED stays terminal —
            // a cancel was an explicit user act and reopening would erase that intent.
            case FAILED, TEST_DONE -> Set.of(READY);
            case CANCELLED -> Set.of();
        };
    }
}
