package com.ai.orange.session;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Set;

/**
 * A session lifecycle event in the one format every harness's reporter sends
 * ({@code POST /sessions/events}; project-space spec §6):
 *
 * <pre>
 * {"event_id": "…", "harness": "claude-code", "session_id": "…", "type": "stopped", "at": "2026-11-10T12:00:00Z",
 *  "node": "laptop", "cwd": "…", "repo_root": "…", "worktree": "…", "branch": "…", "remote_url": "…",
 *  "default_branch": "…", "payload": {"last_message": "…"}}
 * </pre>
 *
 * The reporter resolves git itself: {@code repo_root} is the main checkout (through the common
 * git dir), {@code worktree} the checkout the session is in. Payload by type: {@code user_prompt}
 * has {@code prompt}; {@code stopped} has {@code last_message}, {@code transcript_path} and
 * {@code stop_hook_active}; {@code waiting} has {@code reason} ({@code permission}, {@code input}
 * or {@code idle}) and {@code message}; {@code session_started} has {@code source};
 * {@code session_ended} has {@code reason}.
 */
public record NeutralEvent(String eventId, String harness, String sessionId, String type, OffsetDateTime at,
                           String node, String cwd, String repoRoot, String worktree, String branch,
                           String remoteUrl, String defaultBranch, JsonNode payload) {

    public static final String SESSION_STARTED = "session_started";
    public static final String USER_PROMPT = "user_prompt";
    public static final String STOPPED = "stopped";
    public static final String WAITING = "waiting";
    /** The session is working again after a wait (the user answered a prompt). */
    public static final String ACTIVE = "active";
    public static final String SESSION_ENDED = "session_ended";
    public static final Set<String> TYPES = Set.of(SESSION_STARTED, USER_PROMPT, STOPPED, WAITING, ACTIVE, SESSION_ENDED);

    public static final Set<String> HARNESSES = Set.of("claude-code", "opencode");
    public static final String DEFAULT_NODE = "local";

    /** Parses and validates an event. @throws IllegalArgumentException naming what is wrong */
    public static NeutralEvent parse(JsonNode json) {
        if (json == null || !json.isObject()) throw new IllegalArgumentException("the event must be a JSON object");
        String eventId = text(json, "event_id");
        String harness = text(json, "harness");
        String sessionId = text(json, "session_id");
        String type = text(json, "type");
        if (eventId == null) throw new IllegalArgumentException("event_id is required");
        if (eventId.length() > 200) throw new IllegalArgumentException("event_id is too long");
        if (harness == null || !HARNESSES.contains(harness)) {
            throw new IllegalArgumentException("harness must be one of " + HARNESSES);
        }
        if (sessionId == null) throw new IllegalArgumentException("session_id is required");
        if (type == null || !TYPES.contains(type)) throw new IllegalArgumentException("type must be one of " + TYPES);
        OffsetDateTime at = null;
        String rawAt = text(json, "at");
        if (rawAt != null) {
            try {
                at = OffsetDateTime.parse(rawAt);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("at must be an ISO-8601 time with an offset");
            }
        }
        JsonNode payload = json.path("payload");
        if (!payload.isObject()) payload = com.ai.orange.json.Json.mapper().createObjectNode();
        String node = text(json, "node");
        return new NeutralEvent(eventId, harness, sessionId, type, at, node == null ? DEFAULT_NODE : node,
                text(json, "cwd"), text(json, "repo_root"), text(json, "worktree"), text(json, "branch"),
                text(json, "remote_url"), text(json, "default_branch"), payload);
    }

    /** A payload string, null when absent or blank. */
    public String payloadText(String field) {
        return text(payload, field);
    }

    private static String text(JsonNode json, String field) {
        JsonNode v = json.path(field);
        if (!v.isTextual()) return null;
        String s = v.asText().strip();
        return s.isEmpty() ? null : s;
    }
}
