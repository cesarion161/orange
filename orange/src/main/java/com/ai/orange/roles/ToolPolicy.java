package com.ai.orange.roles;

import com.ai.orange.run.RunKind;
import java.util.ArrayList;
import java.util.List;

/**
 * The tools an agent run gets, by run kind (v2 spec §8; project-space decision 13 for
 * {@code diff_review}). Judge and brief runs get no built-in tool at all, only their worker MCP
 * tools.
 *
 * @param tools           Claude Code's built-in tool set ({@code --tools})
 * @param allowedTools    tools that run without a permission prompt, including the run's worker MCP tools
 * @param permissionMode  {@code --permission-mode}
 * @param mcp             whether the run gets the worker MCP endpoint (and with it a run token)
 * @param projectSettings whether Claude Code loads the settings its working directory carries
 *                        ({@code .claude/settings.json}: hooks included). Not for a diff review: its
 *                        directory is what the reviewed agent committed, and a hook from there would
 *                        run with the reviewer's run token in reach and could submit the verdict itself
 */
public record ToolPolicy(List<String> tools, List<String> allowedTools, String permissionMode, boolean mcp,
                         boolean projectSettings) {

    private static final List<String> EDITING = List.of("Read", "Write", "Edit", "Bash", "Glob", "Grep", "TodoWrite");
    private static final List<String> READING = List.of("Read", "Glob", "Grep");
    /** Unchanged from v1 until the planner gets submit_plan (v2 PR 4). */
    private static final List<String> PLANNING = List.of("Read", "Write", "Glob", "Grep", "WebFetch");

    public ToolPolicy {
        tools = List.copyOf(tools);
        allowedTools = List.copyOf(allowedTools);
    }

    public static ToolPolicy forKind(RunKind kind) {
        return switch (kind) {
            case IMPLEMENT, CI_FIX -> new ToolPolicy(EDITING,
                    plus(EDITING, "mcp__orange__report_result", "mcp__orange__request_human"), "acceptEdits", true, true);
            // No Bash: a reviewer reads; the coordinator puts the diff in its prompt. And none of the
            // settings of the tree it reads: they are the reviewed agent's.
            case DIFF_REVIEW -> new ToolPolicy(READING, plus(READING, "mcp__orange__submit_review"), "dontAsk", true, false);
            case PLAN -> new ToolPolicy(PLANNING, PLANNING, "acceptEdits", false, true);
            // No built-in tools at all: the judge decides from its prompt. It reads text a session
            // wrote, so it gets nothing that text could turn against the user's files.
            case JUDGE -> new ToolPolicy(List.of(), List.of("mcp__orange__judge_continue",
                    "mcp__orange__judge_verify", "mcp__orange__judge_escalate", "mcp__orange__judge_idle",
                    "mcp__orange__propose_item"), "dontAsk", true, true);
            // No built-in tools either: the brief is written from its prompt, which carries text that
            // sessions of every project wrote, and it has no single checkout to read.
            case BRIEF -> new ToolPolicy(List.of(), List.of("mcp__orange__submit_brief"), "dontAsk", true, true);
            case VERIFY, INTEGRATE ->
                    throw new IllegalArgumentException("run kind " + kind.dbValue() + " is not run by a coding agent");
        };
    }

    /**
     * As {@link #forKind}, except for runs about one of the user's sessions: a session's diff review
     * gets no built-in tools, like its judge. It runs outside the user's checkout and reads the diff
     * in its prompt.
     */
    public static ToolPolicy forRun(RunKind kind, boolean attended) {
        if (attended && kind == RunKind.DIFF_REVIEW) {
            return new ToolPolicy(List.of(), List.of("mcp__orange__submit_review"), "dontAsk", true, false);
        }
        return forKind(kind);
    }

    /**
     * The policy for a run inside a container. A run with Bash gets {@code bypassPermissions}: the
     * container is its boundary, and a permission prompt nobody answers would only stop a build.
     * A run without Bash (a review) keeps its mode.
     */
    public ToolPolicy inContainer() {
        return tools.contains("Bash") ? new ToolPolicy(tools, allowedTools, "bypassPermissions", mcp, projectSettings) : this;
    }

    private static List<String> plus(List<String> base, String... extra) {
        List<String> all = new ArrayList<>(base);
        all.addAll(List.of(extra));
        return all;
    }
}
