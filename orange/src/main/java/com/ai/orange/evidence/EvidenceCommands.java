package com.ai.orange.evidence;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code evidence} convention (project-space spec §8) as commands. Its value is a list whose
 * entries are a command string, or {@code {"command": …, "kind": tests|build|lint|custom,
 * "timeout": "PT10M"}}. A single string is a list of one.
 */
public final class EvidenceCommands {

    private EvidenceCommands() {}

    /** Kind when an entry names none: {@code tests} if the command mentions a test, else {@code custom}. */
    static String kindOf(String command) {
        return command.toLowerCase(java.util.Locale.ROOT).contains("test") ? "tests" : "custom";
    }

    /**
     * @throws IllegalArgumentException when the value is not a list of commands; a convention the
     *                                  user wrote wrongly must stop a verification, not pass it
     */
    public static List<EvidenceProperties.Command> parse(JsonNode value) {
        List<EvidenceProperties.Command> commands = new ArrayList<>();
        if (value == null || value.isNull() || value.isMissingNode()) return commands;
        if (value.isTextual()) {
            commands.add(command(value.asText(), null, null));
            return commands;
        }
        if (!value.isArray()) throw new IllegalArgumentException("the evidence convention must be a list of commands");
        for (JsonNode entry : value) {
            if (entry.isTextual()) {
                commands.add(command(entry.asText(), null, null));
            } else if (entry.isObject() && entry.path("command").isTextual()) {
                commands.add(command(entry.get("command").asText(), entry.path("kind").asText(null),
                        entry.path("timeout").asText(null)));
            } else {
                throw new IllegalArgumentException("an evidence entry must be a command or {command, kind, timeout}: "
                        + entry);
            }
        }
        return commands;
    }

    private static EvidenceProperties.Command command(String command, String kind, String timeout) {
        Duration t;
        try {
            t = timeout == null || timeout.isBlank() ? null : Duration.parse(timeout);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("an evidence timeout must be an ISO-8601 duration such as PT10M: "
                    + timeout);
        }
        return new EvidenceProperties.Command(kind == null || kind.isBlank() ? kindOf(command) : kind, command.strip(), t);
    }
}
