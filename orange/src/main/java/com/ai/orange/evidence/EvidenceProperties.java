package com.ai.orange.evidence;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The evidence orange collects itself ({@code orange.evidence}). The command list is for orange's
 * own tasks; the work of the user's sessions is checked with the project's {@code evidence}
 * convention ({@link EvidenceCommands}).
 *
 * @param commands        run in order in the task's worktree at its head; none by default
 * @param outputTailChars how much of each command's output (the end) is kept
 */
@ConfigurationProperties(prefix = "orange.evidence")
public record EvidenceProperties(List<Command> commands, @DefaultValue("4000") int outputTailChars) {

    /** Kinds a configured command may record as; the others come from reviews and people. */
    static final Set<String> COMMAND_KINDS = Set.of("tests", "build", "lint", "custom");

    public EvidenceProperties {
        commands = commands == null ? List.of() : List.copyOf(commands);
        if (outputTailChars < 1) throw new IllegalArgumentException("orange.evidence.output-tail-chars must be positive");
    }

    /**
     * @param kind    {@code tests}, {@code build}, {@code lint} or {@code custom}
     * @param command run as {@code sh -c <command>}
     * @param timeout default 20 minutes
     */
    public record Command(String kind, String command, Duration timeout) {
        public Command {
            if (kind == null || !COMMAND_KINDS.contains(kind)) {
                throw new IllegalArgumentException("evidence command kind must be one of " + COMMAND_KINDS + ": " + kind);
            }
            if (command == null || command.isBlank()) throw new IllegalArgumentException("evidence command is blank");
            if (timeout == null) timeout = Duration.ofMinutes(20);
        }
    }
}
