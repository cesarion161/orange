package com.ai.orange.run;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jooq.JSONB;

/**
 * What an agent reported through the worker MCP endpoint, stored in {@code runs.outcome}.
 * Outcomes are never parsed out of transcripts (v2 anti-goal).
 */
public sealed interface RunOutcome {

    JSONB toJsonb();

    /**
     * A code-changing or planning run's result.
     *
     * @param status   {@code done}, {@code blocked} or {@code failed}
     * @param question set when the agent asked a person ({@code blocked})
     */
    record Result(String status, String summary, String question) implements RunOutcome {
        @Override
        public JSONB toJsonb() {
            ObjectNode node = Json.mapper().createObjectNode().put("type", "result").put("status", status)
                    .put("summary", summary);
            if (question != null) node.put("question", question);
            return JSONB.valueOf(node.toString());
        }
    }

    /**
     * A diff review's verdict.
     *
     * @param verdict {@code pass} or {@code gaps}
     */
    record Review(String verdict, String summary, List<ReviewGap> gaps) implements RunOutcome {
        public Review {
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        @Override
        public JSONB toJsonb() {
            ObjectNode node = Json.mapper().createObjectNode().put("type", "review").put("verdict", verdict)
                    .put("summary", summary);
            ArrayNode array = node.putArray("gaps");
            for (ReviewGap g : gaps) {
                ObjectNode gap = array.addObject();
                if (g.path() != null) gap.put("path", g.path());
                if (g.line() != null) gap.put("line", g.line());
                gap.put("body", g.body());
            }
            return JSONB.valueOf(node.toString());
        }
    }

    static Optional<RunOutcome> parse(JSONB json) {
        if (json == null) return Optional.empty();
        try {
            JsonNode node = Json.mapper().readTree(json.data());
            return switch (node.path("type").asText("")) {
                case "result" -> Optional.of(new Result(node.path("status").asText(), node.path("summary").asText(),
                        node.hasNonNull("question") ? node.get("question").asText() : null));
                case "review" -> {
                    List<ReviewGap> gaps = new ArrayList<>();
                    for (JsonNode g : node.path("gaps")) {
                        gaps.add(new ReviewGap(
                                g.hasNonNull("path") ? g.get("path").asText() : null,
                                g.hasNonNull("line") ? g.get("line").asInt() : null,
                                g.path("body").asText()));
                    }
                    yield Optional.of(new Review(node.path("verdict").asText(), node.path("summary").asText(), gaps));
                }
                default -> Optional.empty();
            };
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
