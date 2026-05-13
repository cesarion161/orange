package com.ai.orange.concurrency;

import com.ai.orange.task.TaskRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Gate consulted before issuing a claim. The chat-as-executor path
 * ({@code ClaimService.claimNext}) routes through this so the operator can
 * throttle parallelism per role without trying to tune the global Temporal cap.
 *
 * <p>Both the gate check and the {@link #usage} snapshot are unsynchronized:
 * counts are read straight from the DB without a serializable lock, so the
 * cap is approximate (see {@link ConcurrencyProperties}).
 */
@Service
public class ConcurrencyGate {

    private final TaskRepository tasks;
    private final ConcurrencyProperties props;

    public ConcurrencyGate(TaskRepository tasks, ConcurrencyProperties props) {
        this.tasks = tasks;
        this.props = props;
    }

    /** True if a new claim for {@code role} would stay within (or there is no) cap. */
    public boolean canClaim(String role) {
        Integer cap = props.capFor(role);
        if (cap == null || cap <= 0) return true;  // no cap configured
        return tasks.countInProgress(role) < cap;
    }

    /**
     * Per-role usage snapshot. Includes every role with a configured cap PLUS
     * any role that currently has in-flight work — that way the operator sees
     * "tester 0/3, dev 5/-" rather than just the configured-rows.
     */
    public Map<String, RoleUsage> usage() {
        Map<String, Integer> configured = props.perRole();
        Set<String> roles = new LinkedHashSet<>(configured.keySet());
        // Add the seeded baseline so even unset roles show up in the report.
        roles.add("dev");
        roles.add("tester");
        roles.add("reviewer");
        roles.add("planner");

        Map<String, RoleUsage> out = new LinkedHashMap<>();
        for (String role : roles) {
            int active = tasks.countInProgress(role);
            Integer cap = configured.get(role);
            out.put(role, new RoleUsage(role, active, cap));
        }
        return out;
    }

    public record RoleUsage(String role, int inProgress, Integer cap) {
        /** True if cap is set AND we're at or above it. */
        public boolean atCap() {
            return cap != null && cap > 0 && inProgress >= cap;
        }
    }
}
