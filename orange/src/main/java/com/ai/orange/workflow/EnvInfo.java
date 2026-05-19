package com.ai.orange.workflow;

import com.ai.orange.agent.protocol.ProtocolCodec;
import com.ai.orange.devenv.DevEnv;
import java.util.LinkedHashMap;
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

    /**
     * Build an {@link EnvInfo} from a {@link DevEnv} row. Used both by the
     * acquire path and by the auto-releaser when reconstructing the snapshot
     * needed by {@code EnvComposeRunner.down}.
     */
    public static EnvInfo from(DevEnv env) {
        Map<String, Integer> ports = parsePorts(env);
        Map<String, Object> meta = parseMetadata(env);
        return new EnvInfo(
                env.id(),
                env.name(),
                ports,
                (String) meta.get("data_dir"),
                (String) meta.get("auth_bypass_token"),
                (String) meta.get("fixture_set"));
    }

    private static Map<String, Integer> parsePorts(DevEnv env) {
        if (env.ports() == null) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> raw = ProtocolCodec.mapper().readValue(env.ports().data(), Map.class);
            Map<String, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                if (e.getValue() instanceof Number n) out.put(e.getKey(), n.intValue());
            }
            return out;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static Map<String, Object> parseMetadata(DevEnv env) {
        if (env.metadata() == null) return Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> out = ProtocolCodec.mapper().readValue(env.metadata().data(), Map.class);
            return out == null ? Map.of() : out;
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
