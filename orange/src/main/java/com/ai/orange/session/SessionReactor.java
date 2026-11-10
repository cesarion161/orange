package com.ai.orange.session;

import com.ai.orange.attention.AttentionItem;
import java.time.Duration;

/**
 * What a session event means: the session counterpart of {@code ReactorDecision}, a pure rule
 * table over state (project-space spec §6). Phase A's rules need no model; the judge of Phase B
 * replaces the question rule.
 */
public final class SessionReactor {

    private SessionReactor() {}

    /** What happens to the session's wait: unchanged, over, or begun for a reason. */
    public enum Wait { KEEP, CLEAR, QUESTION, PERMISSION, FINISHED, JUDGING }

    /** How a stop is decided: by the rules below, by a judge run, or not at all because the loop guard tripped. */
    public enum Judging { OFF, JUDGE, LOOP_GUARD }

    /**
     * @param status      the session's new status; null = unchanged
     * @param waiting      what happens to its wait
     * @param resolveOpen whether its open attention items are moot now, and resolved
     * @param resolvedBy  what resolved them
     * @param raise       the kind of attention item to file; null = none
     * @param userPrompt  whether the user just spoke to it (resets the auto-continue count)
     */
    public record Decision(String status, Wait waiting, boolean resolveOpen, String resolvedBy, String raise,
                           boolean userPrompt) {
        static final Decision NOTHING = new Decision(null, Wait.KEEP, false, null, null, false);
    }

    /**
     * @param status        the session's status before the event
     * @param waitingReason why it is waiting, if it is
     * @param type          the event's type
     * @param waitKind      for a {@code waiting} event: {@code permission}, {@code input} or {@code idle}
     * @param asks          for a {@code stopped} event: whether the last message asks something
     * @param muted         the session's project is archived: nothing is filed
     */
    public static Decision onEvent(String status, String waitingReason, String type, String waitKind, boolean asks,
                                   boolean muted) {
        return onEvent(status, waitingReason, type, waitKind, asks, muted, Judging.OFF);
    }

    /**
     * @param judging for a {@code stopped} event of a session that is not muted: {@code JUDGE} hands the
     *                stop to a judge run; {@code LOOP_GUARD} means orange has answered this session too
     *                many times in a row, so the user is asked instead (spec decision 3)
     */
    public static Decision onEvent(String status, String waitingReason, String type, String waitKind, boolean asks,
                                   boolean muted, Judging judging) {
        if (NeutralEvent.STOPPED.equals(type) && !muted && judging == Judging.JUDGE) {
            return new Decision(Session.JUDGING, Wait.JUDGING, true, "stopped", null, false);
        }
        if (NeutralEvent.STOPPED.equals(type) && !muted && judging == Judging.LOOP_GUARD) {
            return new Decision(Session.STOPPED, Wait.QUESTION, true, "stopped", AttentionItem.SESSION_LOOP, false);
        }
        return switch (type) {
            // A start of a session orange already follows (a resume, a compaction) changes nothing
            // unless it had ended.
            case NeutralEvent.SESSION_STARTED -> Session.ENDED.equals(status)
                    ? new Decision(Session.ACTIVE, Wait.CLEAR, false, null, null, false)
                    : Decision.NOTHING;
            case NeutralEvent.USER_PROMPT -> new Decision(Session.ACTIVE, Wait.CLEAR, true, "user_prompt", null, true);
            case NeutralEvent.ACTIVE -> waitingReason == null && Session.ACTIVE.equals(status)
                    ? Decision.NOTHING
                    : new Decision(Session.ACTIVE, Wait.CLEAR, true, "resumed", null, false);
            case NeutralEvent.STOPPED -> asks
                    ? new Decision(Session.STOPPED, Wait.QUESTION, true, "stopped",
                            muted ? null : AttentionItem.SESSION_QUESTION, false)
                    // Nothing to ask: the sweeper files it if nobody comes back.
                    : new Decision(Session.STOPPED, Wait.FINISHED, true, "stopped", null, false);
            case NeutralEvent.WAITING -> {
                // The harness's idle reminder says nothing a stop has not said, and a second prompt
                // of the same wait is still the same wait.
                if ("idle".equals(waitKind) || Session.WAIT_PERMISSION.equals(waitingReason)) yield Decision.NOTHING;
                yield new Decision(Session.WAITING_ON_USER, Wait.PERMISSION, false, null,
                        muted ? null : AttentionItem.SESSION_WAITING, false);
            }
            case NeutralEvent.SESSION_ENDED -> new Decision(Session.ENDED, Wait.CLEAR, true, "session_ended", null, false);
            default -> Decision.NOTHING;
        };
    }

    /**
     * @param escalate  raise the session's open question or waiting item to urgent
     * @param raiseIdle file that the session finished and nobody came back
     */
    public record Sweep(boolean escalate, boolean raiseIdle) {}

    /** What a wait of this length calls for. */
    public static Sweep onSweep(String waitingReason, Duration waited, Duration escalateAfter, Duration idleAfter,
                                boolean muted) {
        if (muted || waitingReason == null) return new Sweep(false, false);
        return switch (waitingReason) {
            case Session.WAIT_QUESTION, Session.WAIT_PERMISSION -> new Sweep(waited.compareTo(escalateAfter) >= 0, false);
            case Session.WAIT_FINISHED -> new Sweep(false, waited.compareTo(idleAfter) >= 0);
            default -> new Sweep(false, false);
        };
    }
}
