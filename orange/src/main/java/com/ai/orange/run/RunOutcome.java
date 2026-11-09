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
 * What a run produced, stored in {@code runs.outcome}: for agent runs, what the agent reported
 * through the worker MCP endpoint (never parsed out of transcripts, v2 anti-goal); for verify and
 * integrate runs, what orange's own code found. A {@code type} field tells them apart.
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

    /**
     * A verify run's finding.
     *
     * @param stale  the worktree was no longer at the head the run was queued for; nothing was checked
     * @param passed every check passed (false when stale)
     */
    record Verification(boolean stale, boolean passed, List<EvidenceGap> gaps) implements RunOutcome {
        public Verification {
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
        }

        static Verification ofStale() {
            return new Verification(true, false, List.of());
        }

        @Override
        public JSONB toJsonb() {
            ObjectNode node = Json.mapper().createObjectNode().put("type", "verification");
            if (stale) return JSONB.valueOf(node.put("stale", true).toString());
            node.put("passed", passed);
            ArrayNode array = node.putArray("gaps");
            for (EvidenceGap g : gaps) {
                ObjectNode gap = array.addObject().put("source", g.source()).put("command", g.command());
                if (g.exitCode() == null) gap.putNull("exit_code");
                else gap.put("exit_code", g.exitCode());
                gap.put("output_tail", g.outputTail());
            }
            return JSONB.valueOf(node.toString());
        }
    }

    /**
     * An integrate run's result.
     *
     * @param status    {@code integrated}, {@code rebased}, {@code conflict}, {@code base_moved},
     *                  {@code stale} or {@code failed}
     * @param headSha   the integrated head, or the new head after a rebase
     * @param pushed    whether the base was pushed (integrated only)
     * @param localBase {@code updated}, {@code dirty} or {@code skipped} (integrated only)
     * @param files     the conflicting paths (conflict only)
     * @param error     what went wrong (failed only)
     */
    record Integration(String status, String headSha, Boolean pushed, String localBase, List<String> files,
                       String error) implements RunOutcome {
        public Integration {
            files = files == null ? List.of() : List.copyOf(files);
        }

        static Integration of(String status) {
            return new Integration(status, null, null, null, List.of(), null);
        }

        @Override
        public JSONB toJsonb() {
            ObjectNode node = Json.mapper().createObjectNode().put("type", "integration").put("status", status);
            if (headSha != null) node.put("head_sha", headSha);
            if (pushed != null) node.put("pushed", pushed);
            if (localBase != null) node.put("local_base", localBase);
            if (!files.isEmpty()) {
                ArrayNode array = node.putArray("files");
                files.forEach(array::add);
            }
            if (error != null) node.put("error", error);
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
                case "verification" -> {
                    if (node.path("stale").asBoolean(false)) yield Optional.of(Verification.ofStale());
                    List<EvidenceGap> gaps = new ArrayList<>();
                    for (JsonNode g : node.path("gaps")) {
                        gaps.add(new EvidenceGap(g.path("source").asText(), g.path("command").asText(null),
                                g.hasNonNull("exit_code") ? g.get("exit_code").asInt() : null,
                                g.path("output_tail").asText("")));
                    }
                    yield Optional.of(new Verification(false, node.path("passed").asBoolean(false), gaps));
                }
                case "integration" -> {
                    List<String> files = new ArrayList<>();
                    node.path("files").forEach(f -> files.add(f.asText()));
                    yield Optional.of(new Integration(node.path("status").asText(),
                            node.hasNonNull("head_sha") ? node.get("head_sha").asText() : null,
                            node.hasNonNull("pushed") ? node.get("pushed").asBoolean() : null,
                            node.hasNonNull("local_base") ? node.get("local_base").asText() : null,
                            files,
                            node.hasNonNull("error") ? node.get("error").asText() : null));
                }
                default -> Optional.empty();
            };
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
