package com.ai.orange.devenv;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Periodically finds {@code LEASED} dev envs whose holder agent has stopped
 * heartbeating and returns them to the free pool so other pipelines can pick
 * them up.
 *
 * <p>The staleness window is shared with {@link com.ai.orange.agent.HeartbeatReaper}:
 * both read {@code orange.heartbeat.stale-after} (default 5 m) so the two
 * thresholds stay in sync without a second property key.
 *
 * <p>Release logic will be wired in the next task; this commit is purely
 * plumbing to carry the configured {@code staleAfter} duration.
 */
@Service
public class DevEnvAutoReleaser {

    private static final Logger log = LoggerFactory.getLogger(DevEnvAutoReleaser.class);

    private final DevEnvRepository repo;
    private final Duration staleAfter;

    public DevEnvAutoReleaser(DevEnvRepository repo,
                              @Value("${orange.heartbeat.stale-after:5m}") Duration staleAfter) {
        this.repo = repo;
        this.staleAfter = staleAfter;
    }
}
