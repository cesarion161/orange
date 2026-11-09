package com.ai.orange;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** orange runs every agent itself (spec §1): Temporal is gone from the build, not just unused. */
class NoTemporalTest {

    @Test
    void temporalIsNotOnTheClasspath() {
        assertThatThrownBy(() -> Class.forName("io.temporal.client.WorkflowClient"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
