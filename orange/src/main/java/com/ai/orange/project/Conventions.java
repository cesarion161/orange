package com.ai.orange.project;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves a convention (project-space spec §8): a session's override, then the project's value,
 * then the global default. {@code brief-times} and {@code quiet-hours} are global only.
 */
@Component
public class Conventions {

    public static final String QUIET_HOURS = "quiet-hours";
    public static final String BRIEF_TIMES = "brief-times";
    public static final Set<String> GLOBAL_ONLY = Set.of(QUIET_HOURS, BRIEF_TIMES);

    private final SpaceRepository space;
    private final ConventionsProperties defaults;

    public Conventions(SpaceRepository space, ConventionsProperties defaults) {
        this.space = space;
        this.defaults = defaults;
    }

    /** The value in force for a session (may be null) of a project (may be null); empty when unset everywhere. */
    public Optional<JsonNode> resolve(String key, UUID projectId, UUID sessionId) {
        if (projectId != null && !GLOBAL_ONLY.contains(key)) {
            if (sessionId != null) {
                Optional<JsonNode> override = space.activeConvention(projectId, sessionId, key).map(Conventions::value);
                if (override.isPresent()) return override;
            }
            Optional<JsonNode> project = space.activeConvention(projectId, null, key).map(Conventions::value);
            if (project.isPresent()) return project;
        }
        return global(key);
    }

    public Optional<JsonNode> global(String key) {
        return Optional.ofNullable(defaults.get(key));
    }

    /** Every global default, by key. */
    public ObjectNode globals() {
        return defaults.asJson();
    }

    static JsonNode value(SpaceItem item) {
        try {
            return Json.mapper().readTree(item.body().data()).path("value");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("convention " + item.id() + " holds invalid JSON", e);
        }
    }
}
