package com.ai.orange.devenv;

import java.util.Arrays;

public enum DevEnvStatus {
    FREE("free"),
    LEASED("leased"),
    DISABLED("disabled");

    private final String dbValue;

    DevEnvStatus(String dbValue) {
        this.dbValue = dbValue;
    }

    public String dbValue() {
        return dbValue;
    }

    public static DevEnvStatus fromDb(String value) {
        return Arrays.stream(values())
                .filter(s -> s.dbValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown dev_env status: " + value));
    }
}
