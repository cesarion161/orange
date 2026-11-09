package com.ai.orange.task;

import static com.ai.orange.task.TaskStatus.CANCELLED;
import static com.ai.orange.task.TaskStatus.FAILED;
import static com.ai.orange.task.TaskStatus.INTEGRATED;
import static com.ai.orange.task.TaskStatus.NEEDS_HUMAN;
import static com.ai.orange.task.TaskStatus.PENDING;
import static com.ai.orange.task.TaskStatus.READY;
import static com.ai.orange.task.TaskStatus.RUNNING;
import static com.ai.orange.task.TaskStatus.VERIFIED;
import static com.ai.orange.task.TaskStatus.VERIFYING;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The evidence-gated lifecycle (project-space spec §10): one assertion per state's successors. */
class TaskStatusTest {

    @Test
    void transitions() {
        assertThat(PENDING.allowedNext()).containsExactlyInAnyOrder(READY, NEEDS_HUMAN, CANCELLED, FAILED);
        assertThat(READY.allowedNext()).containsExactlyInAnyOrder(RUNNING, NEEDS_HUMAN, CANCELLED, FAILED);
        assertThat(RUNNING.allowedNext()).containsExactlyInAnyOrder(VERIFYING, NEEDS_HUMAN, CANCELLED, FAILED);
        assertThat(VERIFYING.allowedNext()).containsExactlyInAnyOrder(VERIFIED, RUNNING, NEEDS_HUMAN, CANCELLED, FAILED);
        assertThat(VERIFIED.allowedNext())
                .containsExactlyInAnyOrder(INTEGRATED, VERIFYING, RUNNING, NEEDS_HUMAN, CANCELLED, FAILED);
        assertThat(NEEDS_HUMAN.allowedNext()).containsExactlyInAnyOrder(READY, CANCELLED, FAILED);
        assertThat(FAILED.allowedNext()).containsExactly(READY);
        assertThat(INTEGRATED.allowedNext()).isEmpty();
        assertThat(CANCELLED.allowedNext()).isEmpty();
    }

    @Test
    void terminalStates() {
        assertThat(INTEGRATED.isTerminal()).isTrue();
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(NEEDS_HUMAN.isTerminal()).isFalse();
        assertThat(VERIFIED.isTerminal()).isFalse();
    }

    @Test
    void dbValues() {
        assertThat(VERIFYING.dbValue()).isEqualTo("verifying");
        assertThat(NEEDS_HUMAN.dbValue()).isEqualTo("needs_human");
        assertThat(TaskStatus.fromDb("integrated")).isEqualTo(INTEGRATED);
    }
}
