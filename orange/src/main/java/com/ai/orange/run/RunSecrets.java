package com.ai.orange.run;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * The secrets of each run in progress (its credential and its MCP token), held in memory only.
 * What a run reports through the worker MCP endpoint is persisted and fed into later prompts, so
 * it is redacted with these before it is stored, just as the harness output is.
 */
@Component
public class RunSecrets {

    private final Map<UUID, List<String>> byRun = new ConcurrentHashMap<>();

    public void register(UUID runId, Collection<String> secrets) {
        List<String> nonBlank = secrets.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).toList();
        if (!nonBlank.isEmpty()) byRun.put(runId, nonBlank);
    }

    public void forget(UUID runId) {
        byRun.remove(runId);
    }

    /** {@code text} with every secret of {@code runId} replaced by {@code ***}. */
    public String redact(UUID runId, String text) {
        if (text == null) return null;
        String out = text;
        for (String secret : byRun.getOrDefault(runId, List.of())) {
            if (out.contains(secret)) out = out.replace(secret, "***");
        }
        return out;
    }
}
