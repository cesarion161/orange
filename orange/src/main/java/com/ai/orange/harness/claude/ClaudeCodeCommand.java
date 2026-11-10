package com.ai.orange.harness.claude;

import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.RunSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders a {@link RunSpec} into a {@code claude -p} command line and child environment.
 *
 * <p>The prompt is not part of the command line: the adapter writes it to the child's stdin.
 * A prompt starting with {@code -} would otherwise parse as a flag, and Linux limits a single
 * argument to 128 KiB, which a fix-run prompt carrying CI logs can exceed.
 *
 * <p>The environment is built from an allowlist. The coordinator's own environment holds the
 * GitHub token and the LiteLLM master key, so nothing is inherited implicitly, and credential-like
 * variables are dropped even if someone adds them to {@code pass-env}.
 */
public final class ClaudeCodeCommand {

    /** The git identity of every commit orange or its agents make. */
    public static final String BOT_NAME = "orange[bot]";
    public static final String BOT_EMAIL = "orange-bot@users.noreply.github.com";

    /**
     * The variables of {@link #env} whose values are the run's credential. A contained run passes
     * them to docker by name only, so the value is in no command line.
     */
    public static final Set<String> SECRET_ENV = Set.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_AUTH_TOKEN");

    private static final Set<String> NEVER_PASS = Set.of(
            "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_BASE_URL", "CLAUDE_CODE_OAUTH_TOKEN",
            "LITELLM_MASTER_KEY", "ORANGE_GITHUB_TOKEN", "GITHUB_TOKEN", "GH_TOKEN", "GITHUB_WEBHOOK_SECRET");
    private static final Pattern CREDENTIAL_LIKE =
            Pattern.compile("(?i).*(TOKEN|SECRET|PASSWORD|PASSWD|API_KEY|MASTER_KEY|PRIVATE_KEY).*");

    private ClaudeCodeCommand() {}

    public static List<String> argv(RunSpec s, List<String> commandPrefix) {
        List<String> a = new ArrayList<>(commandPrefix);
        a.add("-p");
        a.add("--output-format");
        a.add("stream-json");
        a.add("--verbose");
        a.add("--model");
        a.add(s.model());
        a.add("--effort");
        a.add(s.effort().cli());
        a.add("--session-id");
        a.add(s.runId().toString());
        // orange keeps its own (redacted) copy of the stream; Claude Code's transcript would be
        // a second, unredacted one on disk.
        a.add("--no-session-persistence");
        // Only the repo's own settings apply; the user's ~/.claude settings, hooks and plugins don't.
        a.add("--setting-sources");
        a.add("project,local");
        a.add("--strict-mcp-config");
        if (s.permissionMode() != null && !s.permissionMode().isBlank()) {
            a.add("--permission-mode");
            a.add(s.permissionMode());
        }
        if (s.appendSystemPrompt() != null && !s.appendSystemPrompt().isBlank()) {
            a.add("--append-system-prompt");
            a.add(s.appendSystemPrompt());
        }
        if (s.tools() != null) {
            a.add("--tools");
            if (s.tools().isEmpty()) {
                a.add("");
            } else {
                a.addAll(s.tools());
            }
        }
        // One argument per tool: patterns such as "Bash(git *)" contain spaces.
        if (s.allowedTools() != null && !s.allowedTools().isEmpty()) {
            a.add("--allowedTools");
            a.addAll(s.allowedTools());
        }
        if (s.disallowedTools() != null && !s.disallowedTools().isEmpty()) {
            a.add("--disallowedTools");
            a.addAll(s.disallowedTools());
        }
        if (s.mcpConfig() != null) {
            a.add("--mcp-config");
            a.add(s.mcpConfig().toString());
        }
        return a;
    }

    /**
     * The allowlisted part of {@code hostEnv}: the {@code passEnv} names that are set, minus
     * anything credential-like. Agents and orange's own evidence commands get only this.
     */
    public static Map<String, String> passThrough(Map<String, String> hostEnv, List<String> passEnv) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : passEnv) {
            if (NEVER_PASS.contains(name) || CREDENTIAL_LIKE.matcher(name).matches()) continue;
            String value = hostEnv.get(name);
            if (value != null) env.put(name, value);
        }
        return env;
    }

    public static Map<String, String> env(RunSpec s, Map<String, String> hostEnv, List<String> passEnv,
                                          Path claudeConfigDir) {
        Map<String, String> env = passThrough(hostEnv, passEnv);

        // A per-run config dir keeps the user's own Claude Code login, plugins, hooks and memory out.
        env.put("CLAUDE_CONFIG_DIR", claudeConfigDir.toString());
        if (s.smallModel() != null) env.put("ANTHROPIC_DEFAULT_HAIKU_MODEL", s.smallModel());
        env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        env.put("DISABLE_AUTOUPDATER", "1");
        env.put("GIT_AUTHOR_NAME", BOT_NAME);
        env.put("GIT_AUTHOR_EMAIL", BOT_EMAIL);
        env.put("GIT_COMMITTER_NAME", BOT_NAME);
        env.put("GIT_COMMITTER_EMAIL", BOT_EMAIL);

        switch (s.credentials()) {
            case Credentials.Subscription sub -> env.put("CLAUDE_CODE_OAUTH_TOKEN", sub.oauthToken());
            case Credentials.Gateway gw -> {
                env.put("ANTHROPIC_BASE_URL", gw.baseUrl());
                env.put("ANTHROPIC_AUTH_TOKEN", gw.virtualKey());
                // Claude Code turns tool search off for non-Anthropic base URLs unless told otherwise.
                env.put("ENABLE_TOOL_SEARCH", "true");
            }
        }
        return env;
    }
}
