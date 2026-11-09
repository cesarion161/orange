package com.ai.orange.run;

import com.ai.orange.billing.PlanLimit;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * What a run's failure reason means for the task and the scheduler.
 *
 * <ul>
 *   <li><b>Infra, pausing</b>: the next run would fail the same way at once (no token, a plan
 *       limit, the gateway down, a missing binary, a broken base repo). Not counted against the
 *       task; the scheduler pauses instead.</li>
 *   <li><b>Infra, not pausing</b>: the run was stopped from outside (cancel, a lost lease, a
 *       coordinator restart), or integration hit git or remote trouble. Not counted, no pause.</li>
 *   <li><b>Model</b>: everything else ({@code timeout}, {@code budget}, {@code error_result},
 *       {@code no_result}, {@code exit_<n>}, {@code no_outcome}, {@code reported_failed},
 *       {@code no_commits}). Each adds one to the task's {@code failures}.</li>
 * </ul>
 */
public final class FailureReasons {

    public static final Set<String> PAUSING = Set.of("subscription_token_missing", "auth_failed", "plan_limit",
            "gateway_unavailable", "start_failed", "workspace_failed");
    /** {@code integrate_failed} (git or remote trouble while integrating) sends the task to a person instead. */
    public static final Set<String> NOT_COUNTED = Set.of("cancelled", "lease_expired", "lease_lost",
            "coordinator_restart", "integrate_failed");

    /** How long a plan-limit pause lasts when the message names no reset time. */
    static final Duration PLAN_LIMIT_FALLBACK = Duration.ofHours(1);
    /** How long to wait before trying the gateway again. */
    static final Duration GATEWAY_RETRY = Duration.ofMinutes(5);

    private FailureReasons() {}

    public static boolean isInfra(String reason) {
        return reason != null && (PAUSING.contains(reason) || NOT_COUNTED.contains(reason));
    }

    /** Whether the failure counts against the task (and towards escalation). */
    public static boolean isModelFailure(String reason) {
        return reason != null && !isInfra(reason);
    }

    /**
     * The pause a failure calls for, if any.
     *
     * @param resultText the run's result or last message, where a plan limit names its reset time
     */
    public static Optional<SchedulerPause.Pause> pauseFor(String reason, String resultText, ZonedDateTime now) {
        if (reason == null || !PAUSING.contains(reason)) return Optional.empty();
        ZonedDateTime until = switch (reason) {
            case "plan_limit" -> PlanLimit.resetsAt(resultText, now).orElse(now.plus(PLAN_LIMIT_FALLBACK));
            case "gateway_unavailable" -> now.plus(GATEWAY_RETRY);
            default -> null; // open-ended: a person has to fix the token, the binary or the repo
        };
        return Optional.of(new SchedulerPause.Pause(reason, until == null ? null : until.toOffsetDateTime(),
                now.toOffsetDateTime()));
    }
}
