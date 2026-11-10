package com.ai.orange.project;

import com.ai.orange.evidence.EvidenceCommands;
import com.ai.orange.evidence.EvidenceProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The conventions that say what closing a piece of work takes (project-space spec §8): {@code done},
 * {@code integration}, {@code evidence}, {@code base-branch} and {@code remote-push}, each resolved
 * session → project → global.
 */
@Component
public class WorkConventions {

    public static final String DONE = "done";
    public static final String INTEGRATION = "integration";
    public static final String EVIDENCE = "evidence";
    public static final String BASE_BRANCH = "base-branch";
    public static final String REMOTE_PUSH = "remote-push";

    /** {@code done} entries orange can check. */
    public static final String COMMITTED = "committed";
    public static final String INTEGRATED = "integrated";

    /** {@code integration} values. */
    public static final String MERGE = "merge";
    public static final String BRANCH = "branch";
    public static final String ASK = "ask";
    public static final String PR = "pr";

    private final Conventions conventions;

    public WorkConventions(Conventions conventions) {
        this.conventions = conventions;
    }

    /** What closing requires; {@code evidence} and {@code integrated} when nothing says otherwise. */
    public Set<String> done(UUID projectId, UUID sessionId) {
        Optional<JsonNode> value = conventions.resolve(DONE, projectId, sessionId);
        Set<String> done = new LinkedHashSet<>();
        if (value.isEmpty()) return Set.of(EVIDENCE, INTEGRATED);
        if (value.get().isTextual()) done.add(value.get().asText());
        else value.get().forEach(v -> done.add(v.asText()));
        return done;
    }

    /** {@code merge}, {@code branch}, {@code ask} or {@code pr}. */
    public String integration(UUID projectId, UUID sessionId) {
        return conventions.resolve(INTEGRATION, projectId, sessionId).map(v -> v.asText(MERGE)).orElse(MERGE);
    }

    /** The integration target the user named, if any; the repo's own default applies otherwise. */
    public Optional<String> baseBranch(UUID projectId, UUID sessionId) {
        return conventions.resolve(BASE_BRANCH, projectId, sessionId).map(v -> v.asText("")).filter(b -> !b.isBlank());
    }

    /** Whether the base is pushed after an integration when the repo has a remote; yes unless turned off. */
    public boolean remotePush(UUID projectId, UUID sessionId) {
        return conventions.resolve(REMOTE_PUSH, projectId, sessionId).map(v -> v.asBoolean(true)).orElse(true);
    }

    /**
     * The commands that are evidence for this work; none when the convention is unset.
     *
     * @throws IllegalArgumentException when the convention is not a list of commands
     */
    public List<EvidenceProperties.Command> evidence(UUID projectId, UUID sessionId) {
        return conventions.resolve(EVIDENCE, projectId, sessionId).map(EvidenceCommands::parse).orElse(List.of());
    }
}
