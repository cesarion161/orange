package com.ai.orange.roles;

import com.ai.orange.run.RunKind;
import java.util.ArrayList;
import java.util.List;

/**
 * The tools an agent run gets, by run kind (v2 spec §8; project-space decision 13 for
 * {@code diff_review}).
 *
 * @param tools          Claude Code's built-in tool set ({@code --tools})
 * @param allowedTools   tools that run without a permission prompt, including the run's worker MCP tools
 * @param permissionMode {@code --permission-mode}
 * @param mcp            whether the run gets the worker MCP endpoint (and with it a run token)
 */
public record ToolPolicy(List<String> tools, List<String> allowedTools, String permissionMode, boolean mcp) {

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
                    plus(EDITING, "mcp__orange__report_result", "mcp__orange__request_human"), "acceptEdits", true);
            // No Bash: a reviewer reads; the coordinator puts the diff in its prompt.
            case DIFF_REVIEW -> new ToolPolicy(READING, plus(READING, "mcp__orange__submit_review"), "dontAsk", true);
            case PLAN -> new ToolPolicy(PLANNING, PLANNING, "acceptEdits", false);
            // Read-only and no shell (decision 13): the same exposure as the user reading their own repo.
            case JUDGE -> new ToolPolicy(READING, plus(READING, "mcp__orange__judge_continue",
                    "mcp__orange__judge_verify", "mcp__orange__judge_escalate", "mcp__orange__judge_idle",
                    "mcp__orange__propose_item"), "dontAsk", true);
            case VERIFY, INTEGRATE, BRIEF ->
                    throw new IllegalArgumentException("run kind " + kind.dbValue() + " is not run by a coding agent");
        };
    }

    private static List<String> plus(List<String> base, String... extra) {
        List<String> all = new ArrayList<>(base);
        all.addAll(List.of(extra));
        return all;
    }
}
