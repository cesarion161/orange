package com.ai.orange.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.harness.RunEvent;
import com.ai.orange.harness.RunExit;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/** How a docker client that could not start its container is told from an agent that failed. */
class ContainerFailureTest {

    static RunEvent.Result result(boolean isError, String text) {
        return new RunEvent.Result(isError, isError ? "error_during_execution" : "success", text, null, 0, 0, 0, 0, 1,
                "session");
    }

    @Test
    void dockersOwnExitCodesWithoutAResultAreContainerUnavailable() {
        for (int code : new int[]{125, 126, 127}) {
            assertThat(WorkerRunner.failureReason(new RunExit(code, null, null), null, true))
                    .as("exit %d in a container", code).isEqualTo("container_unavailable");
        }
    }

    @Test
    void onTheHostTheSameExitCodesAreTheProcessesOwn() {
        for (int code : new int[]{125, 126, 127}) {
            assertThat(WorkerRunner.failureReason(new RunExit(code, null, null), null, false))
                    .as("exit %d on the host", code).isEqualTo("no_result");
        }
    }

    @Test
    void aResultMeansTheContainerRan() {
        // Claude Code started and said something: whatever its exit code, docker did its part.
        assertThat(WorkerRunner.failureReason(new RunExit(127, null, result(true, "boom")), null, true))
                .isEqualTo("error_result");
        assertThat(WorkerRunner.failureReason(new RunExit(125, null, result(false, "Done.")), null, true))
                .isEqualTo("exit_125");
        assertThat(WorkerRunner.failureReason(new RunExit(0, null, result(false, "Done.")), null, true)).isNull();
    }

    @Test
    void otherExitCodesAndKillsAreWhatTheyWere() {
        assertThat(WorkerRunner.failureReason(new RunExit(1, null, null), null, true)).isEqualTo("no_result");
        // A removed container ends its client with 137.
        assertThat(WorkerRunner.failureReason(new RunExit(137, "timeout", null), null, true)).isEqualTo("timeout");
        assertThat(WorkerRunner.failureReason(new RunExit(125, "cancelled", null), null, true)).isEqualTo("cancelled");
    }

    @Test
    void containerUnavailablePausesOpenEndedAndDoesNotCount() {
        assertThat(FailureReasons.PAUSING).contains("container_unavailable");
        assertThat(FailureReasons.isInfra("container_unavailable")).isTrue();
        assertThat(FailureReasons.isModelFailure("container_unavailable")).isFalse();
        assertThat(FailureReasons.pauseFor("container_unavailable", null, ZonedDateTime.now()))
                .hasValueSatisfying(p -> {
                    assertThat(p.reason()).isEqualTo("container_unavailable");
                    assertThat(p.until()).as("until a person builds the image or starts Docker").isNull();
                });
    }
}
