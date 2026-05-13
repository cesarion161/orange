package com.ai.orange.workflow;

import java.util.Map;
import java.util.UUID;

/**
 * Snapshot of a leased {@code dev_env}, threaded from {@code acquireDevEnv}
 * through {@code runStage} into the agent's prompt. Temporal-serializable
 * (records + primitives + Map of strings/integers).
 *
 * <p>{@code ports} drives URL construction in the tester prompt — port keys
 * match the V3 seed convention ({@code web}, {@code api}, {@code db}) but
 * projects can use any keys; the prompt renders them verbatim.
 *
 * <p>{@code dataDir} is the per-env storage root; {@code authBypassToken} is a
 * short stable token the running app honors only when bound to a leased env
 * (saves testers from automating login). Both are pulled from the env's
 * {@code metadata} JSONB at lease time and may be null if not seeded.
 */
public record EnvInfo(
        UUID envId,
        String name,
        Map<String, Integer> ports,
        String dataDir,
        String authBypassToken,
        String fixtureSet) {
}
