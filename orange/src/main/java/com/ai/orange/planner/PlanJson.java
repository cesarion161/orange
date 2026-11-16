package com.ai.orange.planner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Wire format the planner agent writes to {@code plan.json}, matching the
 * contract baked into the planner's system prompt. Unknown fields (such as the
 * v1 {@code pipeline}) are ignored, so older plans still parse.
 *
 * <p>{@code overrides} is a small closed-vocabulary bag of per-task flags the
 * orchestrator may consult (e.g. {@code skip_review}, {@code requires_auth}).
 * Stored as-is in {@code tasks.metadata.overrides}; unrecognized keys are
 * preserved but ignored.
 *
 * <p>{@code ask_first} and {@code reason} are the planner's judgement on whether the user should see
 * the plan before it dispatches (remote-first spec §7.2); {@link PlanApproval} has the last word.
 * {@code milestone} is the id of a milestone from the planner's prompt that the plan serves (orange
 * checks it), and {@code evidence} the commands the planner proposes when none are in force: they
 * go in force only when the user approves the plan. {@code evidence} is never null.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanJson(List<PlanTask> tasks, List<PlanEdge> edges, String project, String repo,
                       @JsonProperty("ask_first") Boolean askFirst, String reason, String milestone,
                       List<String> evidence) {

    /** {@code askFirst} is boxed only for the wire (a missing field is null there); it is never null here. */
    public PlanJson {
        askFirst = Boolean.TRUE.equals(askFirst);
        evidence = evidence == null ? List.of()
                : evidence.stream().filter(c -> c != null && !c.isBlank()).map(String::strip).toList();
    }

    /** A plan that names no milestone and proposes no evidence. */
    public PlanJson(List<PlanTask> tasks, List<PlanEdge> edges, String project, String repo, Boolean askFirst,
                    String reason) {
        this(tasks, edges, project, repo, askFirst, reason, null, null);
    }

    /** A plan without the planner's judgement on asking first. */
    public PlanJson(List<PlanTask> tasks, List<PlanEdge> edges, String project, String repo) {
        this(tasks, edges, project, repo, false, null);
    }

    /**
     * A plan of no project (the legacy base repo). {@code project} names one (id or name) and
     * {@code repo} one of its repos ({@code owner/name}); a project with one repo needs no repo.
     */
    public PlanJson(List<PlanTask> tasks, List<PlanEdge> edges) {
        this(tasks, edges, null, null);
    }

    /** At most this many proposed commands, each at most {@link #MAX_COMMAND} characters. */
    public static final int MAX_COMMANDS = 5;
    public static final int MAX_COMMAND = 300;
    private static final Pattern LINE_BREAK = Pattern.compile("[\\n\\r\\u000B\\u000C\\u0085\\u2028\\u2029]");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");
    /** Format characters: bidi marks and overrides, zero-width ones; what reads one way and runs another. */
    private static final Pattern INVISIBLE = Pattern.compile("\\p{Cf}");

    /**
     * Why orange refuses the planner's proposed commands, as a whole: one of them has a line break, a
     * control or an invisible (format) character, or is longer than {@link #MAX_COMMAND} characters, or
     * there are more than {@link #MAX_COMMANDS}. The user approves what the approval item shows, so a
     * command must read the way it runs. Empty when orange takes them. The words never quote a command.
     */
    public static Optional<String> evidenceRefusal(List<String> commands) {
        if (commands == null || commands.isEmpty()) return Optional.empty();
        if (commands.size() > MAX_COMMANDS) {
            return Optional.of(commands.size() + " commands, more than " + MAX_COMMANDS);
        }
        for (int i = 0; i < commands.size(); i++) {
            String c = commands.get(i);
            String which = "command " + (i + 1);
            if (LINE_BREAK.matcher(c).find()) return Optional.of(which + " has a line break");
            if (CONTROL.matcher(c).find()) return Optional.of(which + " has a control character");
            if (INVISIBLE.matcher(c).find()) return Optional.of(which + " has an invisible (format) character");
            if (c.length() > MAX_COMMAND) {
                return Optional.of(which + " is longer than " + MAX_COMMAND + " characters");
            }
        }
        return Optional.empty();
    }

    /** This plan, proposing no evidence. */
    public PlanJson withoutEvidence() {
        return new PlanJson(tasks, edges, project, repo, askFirst, reason, milestone, List.of());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanTask(
            String key,
            String title,
            String description,
            String role,
            Integer priority,
            Map<String, Object> overrides,
            Boolean risk,
            Boolean review,
            String repo,
            @JsonProperty("risk_reason") String riskReason) {

        /** A task without the {@code risk} and {@code review} flags. */
        public PlanTask(String key, String title, String description, String role, Integer priority,
                        Map<String, Object> overrides) {
            this(key, title, description, role, priority, overrides, null, null, null, null);
        }

        /** A task in the plan's own repo. */
        public PlanTask(String key, String title, String description, String role, Integer priority,
                        Map<String, Object> overrides, Boolean risk, Boolean review) {
            this(key, title, description, role, priority, overrides, risk, review, null, null);
        }

        /** A task without a reason for its {@code risk}. */
        public PlanTask(String key, String title, String description, String role, Integer priority,
                        Map<String, Object> overrides, Boolean risk, Boolean review, String repo) {
            this(key, title, description, role, priority, overrides, risk, review, repo, null);
        }

        // Compact constructor only enforces non-null key; the rest is enforced
        // when we turn this into a TaskDef.
        public PlanTask {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("plan task missing key");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlanEdge(String from, String to) {}
}
