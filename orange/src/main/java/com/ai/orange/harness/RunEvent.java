package com.ai.orange.harness;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;

/**
 * A harness-neutral event from one agent run. Adapters translate their harness's output stream
 * into these; nothing downstream reads a harness's raw format.
 */
public sealed interface RunEvent {

    /** The harness started a session. */
    record Init(String sessionId, String model) implements RunEvent {}

    /** Text the model wrote. */
    record AssistantText(String text) implements RunEvent {}

    /** The model called a tool. */
    record ToolUse(String id, String name, JsonNode input) implements RunEvent {}

    /** A tool returned. {@code excerpt} is at most {@link #MAX_EXCERPT} characters. */
    record ToolResult(String toolUseId, boolean isError, String excerpt) implements RunEvent {
        public static final int MAX_EXCERPT = 2000;
    }

    /**
     * Token usage of one model response. A harness may repeat the same response's usage across
     * several events (one per content block), so consumers that sum usage must key it by
     * {@code messageId} and keep the latest value per message.
     */
    record Usage(String messageId, String model, long inputTokens, long outputTokens,
                 long cacheReadTokens, long cacheWriteTokens) implements RunEvent {}

    /** The run's final outcome as the harness reports it. {@code costUsd} is null when not reported. */
    record Result(boolean isError, String subtype, String text, BigDecimal costUsd, long inputTokens,
                  long outputTokens, long cacheReadTokens, long cacheWriteTokens, int numTurns,
                  String sessionId) implements RunEvent {}

    /**
     * The harness is retrying an API call ({@code system/api_retry}). A burst of these is how a
     * rate limit or an outage shows up while a run is still alive.
     */
    record ApiRetry(int attempt, int maxRetries, long retryDelayMs, Integer errorStatus, String error)
            implements RunEvent {}

    /** A line the harness wrote to stderr. */
    record Stderr(String line) implements RunEvent {}

    /** A stdout line this adapter does not understand (new event type, partial line, noise). */
    record Unparsed(String line) implements RunEvent {}
}
