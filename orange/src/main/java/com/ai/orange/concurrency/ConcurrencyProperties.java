package com.ai.orange.concurrency;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-role parallelism caps. Configured via
 * {@code orange.concurrency.per-role.<role>: <int>} in {@code application.yaml}
 * (or env override). A missing entry means unlimited for that role.
 *
 * <p>Example:
 * <pre>
 * orange:
 *   concurrency:
 *     per-role:
 *       tester: 3   # at most 3 in_progress tester tasks at once
 *       dev: 10     # at most 10 in_progress dev tasks
 *       # planner: unset → unlimited
 * </pre>
 *
 * <p>The cap is approximate (no transactional locking around the count). Two
 * workers racing past the same threshold may both succeed; the next claim
 * after that will be refused. Good enough as a throttle, not a hard quota.
 */
@ConfigurationProperties(prefix = "orange.concurrency")
public record ConcurrencyProperties(Map<String, Integer> perRole) {

    public ConcurrencyProperties {
        // Tolerate "per-role:" with no children in yaml (binds to null, not Map.of()).
        if (perRole == null) perRole = Map.of();
    }

    public Integer capFor(String role) {
        return perRole.get(role);
    }
}
