package com.ai.orange.run;

import java.util.Arrays;

public enum RunStatus {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    CANCELLED("cancelled");

    private final String dbValue;

    RunStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static RunStatus fromDb(String value) {
        return Arrays.stream(values())
                .filter(s -> s.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown run status: " + value));
    }

    public boolean isActive() {
        return this == QUEUED || this == RUNNING;
    }
}
