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
     *                  {@code stale}, {@code failed}, or for a session on the base branch
     *                  {@code behind} (origin's base has commits the session lacks)
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

    /**
     * A judge's decision at a session's stop (project-space spec §7).
     *
     * @param kind           {@code continue}, {@code verify}, {@code escalate} or {@code idle}
     * @param message        continue: what the session is told
     * @param confidence     continue: the judge's own 0–1 rating that the user would answer the same
     * @param question       escalate: what the user is asked
     * @param proposedAnswer escalate: the judge's own answer, when it has one
     * @param urgency        escalate: {@code normal}, {@code urgent} or {@code critical}
     */
    record Verdict(String kind, String message, String rationale, Double confidence, String question,
                   String proposedAnswer, String urgency) implements RunOutcome {
        @Override
        public JSONB toJsonb() {
            ObjectNode node = Json.mapper().createObjectNode().put("type", "verdict").put("kind", kind);
            if (message != null) node.put("message", message);
            if (rationale != null) node.put("rationale", rationale);
            if (confidence != null) node.put("confidence", confidence);
            if (question != null) node.put("question", question);
            if (proposedAnswer != null) node.put("proposed_answer", proposedAnswer);
            if (urgency != null) node.put("urgency", urgency);
            return JSONB.valueOf(node.toString());
        }
    }

    /**
     * What orange found when it checked the work of one of the user's sessions.
     *
     * @param status     {@code passed}; {@code gaps} (uncommitted work or a failing command);
     *                   {@code no_evidence} (the project has no evidence commands, so nothing can
     *                   show the work is done); {@code unavailable} (no checkout orange can check)
     * @param headSha    the commit that was checked
     * @param baseSha    where the work left its base; null when there is no base to compare with
     * @param baseBranch the branch the work integrates onto; null when none is known
     * @param detected   commands the checkout suggests ({@code no_evidence} only)
     * @param note       why, for {@code unavailable}
     */
    record SessionCheck(String status, String headSha, String baseSha, String branch, String baseBranch,
                        List<EvidenceGap> gaps, List<String> detected, String note) implements RunOutcome {
        public static final String PASSED = "passed";
        public static final String GAPS = "gaps";
        public static final String NO_EVIDENCE = "no_evidence";
        public static final String UNAVAILABLE = "unavailable";

        public SessionCheck {
            gaps = gaps == null ? List.of() : List.copyOf(gaps);
            detected = detected == null ? List.of() : List.copyOf(detected);
        }

        static SessionCheck unavailable(String note) {
            return new SessionCheck(UNAVAILABLE, null, null, null, null, List.of(), List.of(), note);
        }

        @Override
        public JSONB toJsonb() {
            ObjectNode node = Json.mapper().createObjectNode().put("type", "session_check").put("status", status);
            if (headSha != null) node.put("head_sha", headSha);
            if (baseSha != null) node.put("base_sha", baseSha);
            if (branch != null) node.put("branch", branch);
            if (baseBranch != null) node.put("base_branch", baseBranch);
            if (note != null) node.put("note", note);
            ArrayNode array = node.putArray("gaps");
            for (EvidenceGap g : gaps) {
                ObjectNode gap = array.addObject().put("source", g.source()).put("command", g.command());
                if (g.exitCode() == null) gap.putNull("exit_code");
                else gap.put("exit_code", g.exitCode());
                gap.put("output_tail", g.outputTail());
            }
            ArrayNode d = node.putArray("detected");
            detected.forEach(d::add);
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
                case "session_check" -> {
                    List<EvidenceGap> gaps = new ArrayList<>();
                    for (JsonNode g : node.path("gaps")) {
                        gaps.add(new EvidenceGap(g.path("source").asText(), g.path("command").asText(null),
                                g.hasNonNull("exit_code") ? g.get("exit_code").asInt() : null,
                                g.path("output_tail").asText("")));
                    }
                    List<String> detected = new ArrayList<>();
                    node.path("detected").forEach(d -> detected.add(d.asText()));
                    yield Optional.of(new SessionCheck(node.path("status").asText(),
                            node.hasNonNull("head_sha") ? node.get("head_sha").asText() : null,
                            node.hasNonNull("base_sha") ? node.get("base_sha").asText() : null,
                            node.hasNonNull("branch") ? node.get("branch").asText() : null,
                            node.hasNonNull("base_branch") ? node.get("base_branch").asText() : null,
                            gaps, detected,
                            node.hasNonNull("note") ? node.get("note").asText() : null));
                }
                case "verdict" -> Optional.of(new Verdict(node.path("kind").asText(),
                        node.hasNonNull("message") ? node.get("message").asText() : null,
                        node.hasNonNull("rationale") ? node.get("rationale").asText() : null,
                        node.hasNonNull("confidence") ? node.get("confidence").asDouble() : null,
                        node.hasNonNull("question") ? node.get("question").asText() : null,
                        node.hasNonNull("proposed_answer") ? node.get("proposed_answer").asText() : null,
                        node.hasNonNull("urgency") ? node.get("urgency").asText() : null));
                default -> Optional.empty();
            };
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
