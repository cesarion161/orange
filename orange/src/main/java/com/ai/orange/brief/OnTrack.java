package com.ai.orange.brief;

/**
 * Whether a dated milestone is on track or behind (remote-first spec §7.6). orange's own rule, like
 * {@link Quiet}: the model never labels a milestone, it is told the label.
 *
 * <ul>
 *   <li>no label without a date ({@code elapsedPercent} null) or without tasks;</li>
 *   <li>{@code behind} when one of its tasks is stuck, or when the share of merged tasks trails the share
 *       of the milestone's time gone by more than {@code margin};</li>
 *   <li>{@code on_track} otherwise.</li>
 * </ul>
 */
public final class OnTrack {

    public static final String ON_TRACK = "on_track";
    public static final String BEHIND = "behind";

    /** Shares that differ by less than this are equal: 3 of 10 at 50% with a margin of 0.2 is on track. */
    private static final double EPSILON = 1e-9;

    private OnTrack() {}

    /**
     * @param elapsedPercent how much of the milestone's time has gone, 0 to 100; null when it has no date
     * @param merged         its tasks integrated
     * @param total          its tasks, cancelled ones left out
     * @param stuck          its tasks that wait for a person or failed
     * @param margin         how far, as a share (0.2 = 20 points), merged work may trail the time gone
     * @return {@link #ON_TRACK}, {@link #BEHIND}, or null for no label
     */
    public static String label(Integer elapsedPercent, int merged, int total, int stuck, double margin) {
        if (elapsedPercent == null || total <= 0) return null;
        if (stuck > 0) return BEHIND;
        double mergedShare = (double) merged / total;
        double timeShare = elapsedPercent / 100.0;
        return mergedShare < timeShare - margin - EPSILON ? BEHIND : ON_TRACK;
    }
}
