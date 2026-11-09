package com.ai.orange.routing;

/**
 * What routing needs to know about a run.
 *
 * @param role       routing role (planner, implementer, chore, reviewer, ci_fixer, …)
 * @param difficulty the task's planner-assigned difficulty: S, M or L (null when unknown)
 * @param risk       whether the task is risky (planner flag or risk-path scope)
 * @param failures   failed attempts so far on this task
 */
public record RouteRequest(String role, String difficulty, boolean risk, int failures) {}
