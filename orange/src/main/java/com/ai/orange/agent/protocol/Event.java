package com.ai.orange.agent.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

/**
 * NDJSON events emitted on agent-runner stdout. Mirrors
 * {@code protocol/events.schema.json}.
 *
 * Jackson handles the {@code "type"} discriminator both ways via the
 * {@link JsonTypeInfo}/{@link JsonSubTypes} pair.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Event.Ready.class, name = "ready"),
        @JsonSubTypes.Type(value = Event.AssistantMessage.class, name = "assistant_message"),
        @JsonSubTypes.Type(value = Event.Thinking.class, name = "thinking"),
        @JsonSubTypes.Type(value = Event.ToolUse.class, name = "tool_use"),
        @JsonSubTypes.Type(value = Event.ToolResult.class, name = "tool_result"),
        @JsonSubTypes.Type(value = Event.HookRequest.class, name = "hook_request"),
        @JsonSubTypes.Type(value = Event.SubagentStart.class, name = "subagent_start"),
        @JsonSubTypes.Type(value = Event.SubagentEnd.class, name = "subagent_end"),
        @JsonSubTypes.Type(value = Event.Cost.class, name = "cost"),
        @JsonSubTypes.Type(value = Event.Final.class, name = "final"),
        @JsonSubTypes.Type(value = Event.ErrorEvent.class, name = "error"),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface Event {

    record Ready(@JsonProperty("session_id") String sessionId,
                 @JsonProperty("sdk_version") String sdkVersion) implements Event {}

    record AssistantMessage(String text, Map<String, Long> usage) implements Event {}

    record Thinking(String text) implements Event {}

    record ToolUse(String id, String tool, Map<String, Object> input) implements Event {}

    record ToolResult(String id,
                      @JsonProperty("is_error") boolean isError,
                      String output) implements Event {}

    /** Round-trip permission decision request. The orchestrator answers with a {@link Command.HookResponse} on stdin. */
    record HookRequest(String id, String event, String tool, Map<String, Object> input) implements Event {}

    record SubagentStart(String agent,
                         @JsonProperty("parent_id") String parentId) implements Event {}

    record SubagentEnd(String agent, String summary) implements Event {}

    record Cost(double usd, Map<String, Long> tokens) implements Event {}

    record Final(String status, String summary, Map<String, String> artifacts) implements Event {}

    /**
     * The schema's "error" event. Named {@code ErrorEvent} on the Java side
     * to avoid colliding with {@link java.lang.Error}.
     */
    record ErrorEvent(String code, String message) implements Event {}
}
