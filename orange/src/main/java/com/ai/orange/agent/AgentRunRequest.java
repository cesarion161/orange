package com.ai.orange.agent;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * One agent run to execute.
 *
 * @param taskId     the task the run works on; null for a planner run (no task exists yet)
 * @param role       the stage role ({@code dev}, {@code tester}, {@code reviewer}, {@code planner}, …)
 * @param agent      prompt, tools and permission mode for the role
 * @param prompt     the task prompt
 * @param cwd        where the harness runs (the task worktree, or the planner's output dir)
 * @param difficulty S, M or L for routing; null when unknown
 * @param risk       whether the task is risky (raises the routing floor)
 * @param timeout    the run is killed after this long
 * @param actor      written as the actor of the run's task events
 */
public record AgentRunRequest(
        UUID taskId,
        String role,
        Agent agent,
        String prompt,
        Path cwd,
        String difficulty,
        boolean risk,
        Duration timeout,
        String actor) {}
