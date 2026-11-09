package com.ai.orange.harness.claude;

import com.ai.orange.harness.RunEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * Translates one line of {@code claude -p --output-format stream-json --verbose} output into
 * {@link RunEvent}s. Stateless and total: it never throws, so a new or malformed line from a
 * newer Claude Code release becomes an {@link RunEvent.Unparsed} instead of breaking the run.
 */
public final class StreamJsonParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<RunEvent> parse(String line) {
        if (line == null || line.isBlank()) return List.of();
        JsonNode root;
        try {
            root = MAPPER.readTree(line);
        } catch (Exception e) {
            return List.of(new RunEvent.Unparsed(line));
        }
        if (root == null || !root.isObject()) return List.of(new RunEvent.Unparsed(line));

        try {
            return switch (root.path("type").asText("")) {
                case "system" -> switch (root.path("subtype").asText()) {
                    case "init" -> List.of(new RunEvent.Init(text(root, "session_id"), text(root, "model")));
                    case "api_retry" -> List.of(new RunEvent.ApiRetry(
                            root.path("attempt").asInt(0),
                            root.path("max_retries").asInt(0),
                            root.path("retry_delay_ms").asLong(0),
                            root.path("error_status").isNumber() ? root.path("error_status").asInt() : null,
                            text(root, "error")));
                    default -> List.of(new RunEvent.Unparsed(line));
                };
                case "assistant" -> assistant(root.path("message"));
                case "user" -> toolResults(root.path("message"));
                case "result" -> List.of(result(root));
                default -> List.of(new RunEvent.Unparsed(line));
            };
        } catch (RuntimeException e) {
            return List.of(new RunEvent.Unparsed(line));
        }
    }

    private static List<RunEvent> assistant(JsonNode message) {
        List<RunEvent> out = new ArrayList<>();
        for (JsonNode block : message.path("content")) {
            switch (block.path("type").asText("")) {
                case "text" -> out.add(new RunEvent.AssistantText(block.path("text").asText("")));
                case "tool_use" -> out.add(new RunEvent.ToolUse(
                        text(block, "id"), text(block, "name"), block.path("input")));
                default -> { /* thinking blocks and anything newer are not surfaced */ }
            }
        }
        JsonNode usage = message.path("usage");
        if (usage.isObject()) {
            out.add(new RunEvent.Usage(
                    text(message, "id"),
                    text(message, "model"),
                    usage.path("input_tokens").asLong(0),
                    usage.path("output_tokens").asLong(0),
                    usage.path("cache_read_input_tokens").asLong(0),
                    usage.path("cache_creation_input_tokens").asLong(0)));
        }
        return out;
    }

    private static List<RunEvent> toolResults(JsonNode message) {
        List<RunEvent> out = new ArrayList<>();
        for (JsonNode block : message.path("content")) {
            if (!"tool_result".equals(block.path("type").asText())) continue;
            out.add(new RunEvent.ToolResult(
                    text(block, "tool_use_id"),
                    block.path("is_error").asBoolean(false),
                    excerpt(block.path("content"))));
        }
        return out;
    }

    private static RunEvent.Result result(JsonNode root) {
        JsonNode usage = root.path("usage");
        JsonNode cost = root.get("total_cost_usd");
        return new RunEvent.Result(
                root.path("is_error").asBoolean(false),
                text(root, "subtype"),
                text(root, "result"),
                cost != null && cost.isNumber() ? cost.decimalValue() : null,
                usage.path("input_tokens").asLong(0),
                usage.path("output_tokens").asLong(0),
                usage.path("cache_read_input_tokens").asLong(0),
                usage.path("cache_creation_input_tokens").asLong(0),
                root.path("num_turns").asInt(0),
                text(root, "session_id"));
    }

    /** Tool-result content is either a string or a list of content blocks. */
    private static String excerpt(JsonNode content) {
        String full;
        if (content.isTextual()) {
            full = content.asText();
        } else if (content.isArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonNode b : content) {
                if (b.has("text")) parts.add(b.path("text").asText());
            }
            full = String.join("\n", parts);
        } else {
            full = "";
        }
        return full.length() <= RunEvent.ToolResult.MAX_EXCERPT
                ? full
                : full.substring(0, RunEvent.ToolResult.MAX_EXCERPT);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
