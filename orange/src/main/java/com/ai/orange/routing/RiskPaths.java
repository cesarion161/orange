package com.ai.orange.routing;

import com.ai.orange.worktree.Git;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Whether a diff touches {@code orange.routing.risk.paths}, which makes work risky enough for a
 * review under the {@code risky} convention (remote-first spec §7.3). A diff that cannot be read
 * counts as risky: one review too many is cheaper than one skipped that was due.
 */
@Component
public class RiskPaths {

    private static final Logger log = LoggerFactory.getLogger(RiskPaths.class);

    private final RoutingTable routing;

    public RiskPaths(RoutingTable routing) {
        this.routing = routing;
    }

    /** Whether any path changed between {@code from} and {@code to} in {@code repo} is a risk path. */
    public boolean touches(Path repo, String from, String to) {
        if (from == null || from.isBlank() || to == null || to.isBlank()) {
            log.warn("no diff to read in {} ({}..{}): counting it as risky", repo, from, to);
            return true;
        }
        try {
            String names = Git.run(repo, Git.QUICK, "diff", "--name-only", from, to);
            return names.lines().map(String::strip).filter(n -> !n.isEmpty()).anyMatch(routing::isRiskPath);
        } catch (RuntimeException e) {
            log.warn("could not read the diff {}..{} in {}: counting it as risky ({})", from, to, repo, e.toString());
            return true;
        }
    }
}
