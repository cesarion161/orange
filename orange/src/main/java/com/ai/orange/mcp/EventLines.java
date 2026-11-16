package com.ai.orange.mcp;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * A run event ({@code run_events}, as {@code WorkerRunner} stores them) as one short line for
 * {@code orange_tail}: what the agent said, which tool it called on what, how that went.
 */
final class EventLines {

    static final int MAX = 200;
    /** The input fields that say what a tool call is about, in the order they are looked for. */
    private static final List<String> SUBJECTS = List.of("command", "file_path", "path", "pattern", "url", "query",
            "description", "prompt");

    private EventLines() {}

    static String text(String type, String payloadJson) {
        JsonNode p;
        try {
            p = payloadJson == null ? Json.mapper().createObjectNode() : Json.mapper().readTree(payloadJson);
        } catch (Exception e) {
            return "";
        }
        String line = switch (type) {
            case "init" -> "started on " + p.path("model").asText("?");
            case "assistant" -> p.path("text").asText("");
            case "tool_use" -> p.path("tool").asText("?") + subject(p.path("input"));
            case "tool_result" -> (p.path("is_error").asBoolean() ? "error: " : "ok: ") + p.path("excerpt").asText("");
            case "result" -> (p.path("is_error").asBoolean() ? "ended with an error" : "ended")
                    + (p.hasNonNull("subtype") ? " (" + p.path("subtype").asText() + ")" : "")
                    + (p.hasNonNull("num_turns") ? ", " + p.path("num_turns").asInt() + " turns" : "")
                    + (p.hasNonNull("cost_usd") ? ", $" + p.path("cost_usd").asText() : "");
            case "api_retry" -> "API retry " + p.path("attempt").asInt() + "/" + p.path("max_retries").asInt()
                    + (p.hasNonNull("error") ? ": " + p.path("error").asText() : "");
            default -> p.toString();
        };
        return cut(line);
    }

    private static String subject(JsonNode input) {
        if (input == null || !input.isObject()) return "";
        for (String key : SUBJECTS) {
            if (input.path(key).isTextual() && !input.path(key).asText().isBlank()) return " " + input.path(key).asText();
        }
        return input.isEmpty() ? "" : " " + input;
    }

    private static String cut(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= MAX ? flat : flat.substring(0, MAX - 1) + "…";
    }
}
