package com.ai.orange.roles;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Role instructions, appended to Claude Code's own system prompt. They live as files under
 * {@code src/main/resources/roles/} so they are reviewed like code and versioned with it.
 */
@Component
public class RolePrompts {

    private static final Map<String, String> FILE_BY_ROLE = Map.of(
            "implementer", "implementer.md",
            "chore", "implementer.md",
            "ci_fixer", "implementer.md",
            "reviewer", "reviewer.md",
            "judge", "judge.md",
            "brief", "brief.md",
            "planner", "planner.md");

    private final Map<String, String> loaded = new ConcurrentHashMap<>();

    public String systemPrompt(String role) {
        String file = role == null ? null : FILE_BY_ROLE.get(role);
        if (file == null) throw new IllegalArgumentException("no role prompt for role '" + role + "'");
        return loaded.computeIfAbsent(file, RolePrompts::load);
    }

    private static String load(String file) {
        String path = "/roles/" + file;
        try (InputStream in = RolePrompts.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException(path + " is not on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
