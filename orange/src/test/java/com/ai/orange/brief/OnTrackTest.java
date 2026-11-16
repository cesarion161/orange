package com.ai.orange.brief;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** On track or behind (remote-first spec §7.6): orange's rule, never the model's word. */
class OnTrackTest {

    static final double MARGIN = 0.2;

    @Test
    void noDateNoLabel() {
        assertThat(OnTrack.label(null, 1, 4, 0, MARGIN)).isNull();
        assertThat(OnTrack.label(null, 0, 4, 2, MARGIN)).as("not even when a task is stuck").isNull();
    }

    @Test
    void noTasksNoLabel() {
        assertThat(OnTrack.label(90, 0, 0, 0, MARGIN)).isNull();
    }

    @Test
    void aStuckTaskIsBehind() {
        assertThat(OnTrack.label(10, 3, 4, 1, MARGIN)).isEqualTo(OnTrack.BEHIND);
    }

    @Test
    void trailingByMoreThanTheMarginIsBehind() {
        // 25% merged at 50% of the time gone: 0.25 < 0.50 - 0.20.
        assertThat(OnTrack.label(50, 1, 4, 0, MARGIN)).isEqualTo(OnTrack.BEHIND);
    }

    @Test
    void withinTheMarginIsOnTrack() {
        // 40% merged at 50% of the time gone: 0.40 >= 0.30.
        assertThat(OnTrack.label(50, 2, 5, 0, MARGIN)).isEqualTo(OnTrack.ON_TRACK);
        assertThat(OnTrack.label(0, 0, 3, 0, MARGIN)).as("nothing merged, no time gone").isEqualTo(OnTrack.ON_TRACK);
    }

    @Test
    void exactlyAtTheMarginIsOnTrack() {
        // 30% merged at 50%: the share trails by exactly the margin, not more.
        assertThat(OnTrack.label(50, 3, 10, 0, MARGIN)).isEqualTo(OnTrack.ON_TRACK);
    }

    @Test
    void overdueWithWorkLeftIsBehindAndAllMergedIsOnTrack() {
        assertThat(OnTrack.label(100, 3, 4, 0, MARGIN)).isEqualTo(OnTrack.BEHIND);
        assertThat(OnTrack.label(100, 4, 4, 0, MARGIN)).isEqualTo(OnTrack.ON_TRACK);
    }
}
