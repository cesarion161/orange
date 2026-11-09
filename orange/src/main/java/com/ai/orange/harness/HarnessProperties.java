package com.ai.orange.harness;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Harness settings ({@code orange.harness}).
 *
 * @param runRoot    per-run working files (stream log, isolated Claude config); outside every git worktree
 * @param claudeCode Claude Code adapter settings
 */
@ConfigurationProperties(prefix = "orange.harness")
public record HarnessProperties(Path runRoot, ClaudeCode claudeCode) {

    /**
     * @param command argv prefix that starts Claude Code; tests point it at a fake
     * @param passEnv host environment variables copied into the child; everything else is dropped
     */
    public record ClaudeCode(List<String> command, List<String> passEnv) {}
}
