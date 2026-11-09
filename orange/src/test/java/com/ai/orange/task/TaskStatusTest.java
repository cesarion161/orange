package com.ai.orange.task;

import static com.ai.orange.task.TaskStatus.CANCELLED;
import static com.ai.orange.task.TaskStatus.FAILED;
import static com.ai.orange.task.TaskStatus.IN_REVIEW;
import static com.ai.orange.task.TaskStatus.MERGED;
import static com.ai.orange.task.TaskStatus.NEEDS_HUMAN;
import static com.ai.orange.task.TaskStatus.PENDING;
import static com.ai.orange.task.TaskStatus.READY;
import static com.ai.orange.task.TaskStatus.RUNNING;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** FSM v2 (spec §6): one assertion per state's allowed successors. */
class TaskStatusTest {

    @Test
    void transitions() {
        assertThat(PENDING.allowedNext()).containsExactlyInAnyOrder(READY, CANCELLED, FAILED);
        assertThat(READY.allowedNext()).containsExactlyInAnyOrder(RUNNING, CANCELLED, FAILED);
        assertThat(RUNNING.allowedNext()).containsExactlyInAnyOrder(IN_REVIEW, NEEDS_HUMAN, MERGED, CANCELLED, FAILED);
        assertThat(IN_REVIEW.allowedNext()).containsExactlyInAnyOrder(MERGED, RUNNING, NEEDS_HUMAN, CANCELLED, FAILED);
        assertThat(NEEDS_HUMAN.allowedNext()).containsExactlyInAnyOrder(READY, MERGED, CANCELLED, FAILED);
        assertThat(FAILED.allowedNext()).containsExactly(READY);
        assertThat(MERGED.allowedNext()).isEmpty();
        assertThat(CANCELLED.allowedNext()).isEmpty();
    }

    @Test
    void terminalStates() {
        assertThat(MERGED.isTerminal()).isTrue();
        assertThat(FAILED.isTerminal()).isTrue();
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(NEEDS_HUMAN.isTerminal()).isFalse();
        assertThat(IN_REVIEW.isTerminal()).isFalse();
    }

    @Test
    void dbValues() {
        assertThat(IN_REVIEW.dbValue()).isEqualTo("in_review");
        assertThat(NEEDS_HUMAN.dbValue()).isEqualTo("needs_human");
        assertThat(TaskStatus.fromDb("merged")).isEqualTo(MERGED);
    }
}
