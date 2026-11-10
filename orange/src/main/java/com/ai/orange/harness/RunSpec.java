package com.ai.orange.harness;

import com.ai.orange.routing.Effort;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Everything a harness adapter needs to start one run. Harness-neutral: each adapter renders it
 * into its own command line, environment and config files.
 *
 * @param runId              also used as the harness session id and the run directory name
 * @param prompt             the task prompt (sent on stdin, never as an argument)
 * @param appendSystemPrompt role instructions appended to the harness's own system prompt; may be null
 * @param smallModel         model the harness uses for its own background calls
 * @param tools              built-in tool set: null = the harness default, empty = no tools at all
 * @param allowedTools       tools that run without a permission prompt
 * @param disallowedTools    tools the run may never use
 * @param permissionMode     harness permission mode, e.g. {@code acceptEdits}; may be null
 * @param mcpConfig          MCP server config file for this run; may be null
 * @param redact             secrets besides the credentials to mask in everything the run
 *                           writes to disk or turns into events, e.g. the run's MCP token
 * @param sandbox            the container the run's process lives in; null = a child process of orange
 * @param projectSettings    whether the harness loads the settings its working directory carries
 *                           (hooks included); false for a run that reads a tree someone else wrote
 */
public record RunSpec(
        UUID runId,
        String prompt,
        String appendSystemPrompt,
        String model,
        String smallModel,
        Effort effort,
        Path cwd,
        List<String> tools,
        List<String> allowedTools,
        List<String> disallowedTools,
        String permissionMode,
        Path mcpConfig,
        Credentials credentials,
        Duration timeout,
        List<String> redact,
        Sandbox sandbox,
        boolean projectSettings) {

    public RunSpec {
        redact = redact == null ? List.of() : List.copyOf(redact);
    }

    /** A run on the host with nothing to redact beyond its credentials, and its directory's own settings. */
    public RunSpec(UUID runId, String prompt, String appendSystemPrompt, String model, String smallModel,
                   Effort effort, Path cwd, List<String> tools, List<String> allowedTools,
                   List<String> disallowedTools, String permissionMode, Path mcpConfig, Credentials credentials,
                   Duration timeout) {
        this(runId, prompt, appendSystemPrompt, model, smallModel, effort, cwd, tools, allowedTools, disallowedTools,
                permissionMode, mcpConfig, credentials, timeout, List.of(), null, true);
    }
}
