package com.ai.orange.agent.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * NDJSON commands written to agent-runner stdin. Mirrors
 * {@code protocol/commands.schema.json}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Command.Start.class, name = "start"),
        @JsonSubTypes.Type(value = Command.HookResponse.class, name = "hook_response"),
        @JsonSubTypes.Type(value = Command.Signal.class, name = "signal"),
        @JsonSubTypes.Type(value = Command.Cancel.class, name = "cancel"),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public sealed interface Command {

    record Start(String prompt, StartOptions options) implements Command {}

    record HookResponse(String id, String decision, String reason) implements Command {

        public static HookResponse allow(String id) {
            return new HookResponse(id, "allow", null);
        }

        public static HookResponse block(String id, String reason) {
            return new HookResponse(id, "block", reason);
        }
    }

    record Signal(String name, Object payload) implements Command {}

    record Cancel(String reason) implements Command {}

    /** Maps to {@code Start.options} in the schema. All fields nullable. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record StartOptions(
            String model,
            @JsonProperty("fallback_model") String fallbackModel,
            @JsonProperty("system_prompt") String systemPrompt,
            @JsonProperty("allowed_tools") List<String> allowedTools,
            @JsonProperty("disallowed_tools") List<String> disallowedTools,
            String cwd,
            @JsonProperty("max_turns") Integer maxTurns,
            @JsonProperty("max_budget_usd") Double maxBudgetUsd,
            @JsonProperty("permission_mode") String permissionMode,
            Map<String, Object> agents,
            @JsonProperty("mcp_servers") Map<String, Object> mcpServers,
            @JsonProperty("hooks_via_runner") List<String> hooksViaRunner) {
    }
}
