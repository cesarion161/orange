package com.ai.orange.harness.claude;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.harness.Credentials;
import com.ai.orange.harness.RunSpec;
import com.ai.orange.routing.Effort;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaudeCodeCommandTest {

    static final UUID RUN = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
    static final List<String> PASS_ENV = List.of("PATH", "LANG", "LC_ALL", "TERM", "TMPDIR", "HOME", "USER");

    static RunSpec spec(List<String> tools, List<String> allowed, List<String> disallowed, String system,
                        Path mcp, Credentials creds) {
        return new RunSpec(RUN, "-- leading dashes must not become flags", system, "claude-sonnet-5-5",
                "claude-haiku-5-5", Effort.XHIGH, Path.of("/work/tree"), tools, allowed, disallowed,
                "acceptEdits", mcp, creds, Duration.ofMinutes(5));
    }

    static RunSpec plain(Credentials creds) {
        return spec(null, List.of(), List.of(), null, null, creds);
    }

    @Test
    void argvHasRequiredFlags() {
        RunSpec s = spec(null, List.of("Read", "Bash(git *)"), List.of("WebFetch"), "You are a dev.",
                Path.of("/runs/x/mcp.json"), new Credentials.Subscription("tok"));
        List<String> argv = ClaudeCodeCommand.argv(s, List.of("claude"));

        assertThat(argv.subList(0, 16)).containsExactly(
                "claude", "-p", "--output-format", "stream-json", "--verbose",
                "--model", "claude-sonnet-5-5", "--effort", "xhigh", "--session-id", RUN.toString(),
                "--no-session-persistence",
                "--setting-sources", "project,local", "--strict-mcp-config", "--permission-mode");
        assertThat(argv.get(16)).isEqualTo("acceptEdits");
        assertThat(argv).containsSequence("--append-system-prompt", "You are a dev.");
        // One element per tool: "Bash(git *)" contains a space and must survive intact.
        assertThat(argv).containsSequence("--allowedTools", "Read", "Bash(git *)");
        assertThat(argv).containsSequence("--disallowedTools", "WebFetch");
        assertThat(argv).containsSequence("--mcp-config", "/runs/x/mcp.json");
    }

    @Test
    void aRunThatMustNotLoadItsDirectorysSettingsNamesOnlyTheUsers() {
        RunSpec host = spec(List.of("Read"), List.of("Read"), List.of(), null, Path.of("/runs/x/mcp.json"),
                new Credentials.Subscription("tok"));
        RunSpec review = new RunSpec(host.runId(), host.prompt(), host.appendSystemPrompt(), host.model(),
                host.smallModel(), host.effort(), host.cwd(), host.tools(), host.allowedTools(), host.disallowedTools(),
                "dontAsk", host.mcpConfig(), host.credentials(), host.timeout(), List.of(), null, false);

        List<String> argv = ClaudeCodeCommand.argv(review, List.of("claude"));

        // CLAUDE_CONFIG_DIR is the run's own empty directory, so "user" loads nothing: no hook of the
        // tree the run reads, and none of the person's.
        assertThat(argv).containsSequence("--setting-sources", "user", "--strict-mcp-config");
        assertThat(argv).doesNotContain("project,local", "project", "local");
        // What orange itself gives the run is on the command line and stays.
        assertThat(argv).containsSequence("--tools", "Read");
        assertThat(argv).containsSequence("--allowedTools", "Read");
        assertThat(argv).containsSequence("--mcp-config", "/runs/x/mcp.json");
        assertThat(ClaudeCodeCommand.argv(host, List.of("claude"))).containsSequence("--setting-sources", "project,local");
    }

    @Test
    void promptIsNeverAnArgument() {
        // The prompt goes to stdin: a leading "-" would parse as a flag, and Linux caps one
        // argument at 128 KiB (fix-run prompts carry CI logs).
        List<String> argv = ClaudeCodeCommand.argv(plain(new Credentials.Subscription("tok")), List.of("claude"));
        assertThat(argv).noneMatch(a -> a.contains("leading dashes"));
        assertThat(argv).doesNotContain("--append-system-prompt", "--allowedTools", "--disallowedTools",
                "--mcp-config", "--tools");
    }

    @Test
    void commandPrefixMayHaveSeveralElements() {
        List<String> argv = ClaudeCodeCommand.argv(plain(new Credentials.Subscription("tok")),
                List.of("/usr/bin/java", "/tmp/FakeClaude.java"));
        assertThat(argv.subList(0, 3)).containsExactly("/usr/bin/java", "/tmp/FakeClaude.java", "-p");
    }

    @Test
    void noToolsMeansEmptyToolsFlag() {
        List<String> none = ClaudeCodeCommand.argv(
                spec(List.of(), List.of(), List.of(), null, null, new Credentials.Subscription("t")), List.of("claude"));
        assertThat(none).containsSequence("--tools", "");

        List<String> some = ClaudeCodeCommand.argv(
                spec(List.of("Read", "Grep"), List.of(), List.of(), null, null, new Credentials.Subscription("t")),
                List.of("claude"));
        assertThat(some).containsSequence("--tools", "Read", "Grep");
    }

    @Test
    void envIsAllowlistOnly() {
        Map<String, String> host = Map.of(
                "PATH", "/bin", "HOME", "/Users/me",
                "ORANGE_GITHUB_TOKEN", "x", "LITELLM_MASTER_KEY", "y", "ANTHROPIC_API_KEY", "z",
                "GITHUB_WEBHOOK_SECRET", "w", "SOME_RANDOM_VAR", "v");
        Map<String, String> env = ClaudeCodeCommand.env(plain(new Credentials.Subscription("tok")), host, PASS_ENV,
                Path.of("/runs/r1/claude-config"));

        assertThat(env).containsEntry("PATH", "/bin").containsEntry("HOME", "/Users/me");
        assertThat(env).doesNotContainKeys("ORANGE_GITHUB_TOKEN", "LITELLM_MASTER_KEY", "ANTHROPIC_API_KEY",
                "GITHUB_WEBHOOK_SECRET", "SOME_RANDOM_VAR");
        assertThat(env)
                .containsEntry("CLAUDE_CONFIG_DIR", "/runs/r1/claude-config")
                .containsEntry("ANTHROPIC_DEFAULT_HAIKU_MODEL", "claude-haiku-5-5")
                .containsEntry("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1")
                .containsEntry("DISABLE_AUTOUPDATER", "1")
                .containsEntry("GIT_AUTHOR_NAME", "orange[bot]")
                .containsEntry("GIT_COMMITTER_NAME", "orange[bot]")
                .containsEntry("GIT_AUTHOR_EMAIL", "orange-bot@users.noreply.github.com")
                .containsEntry("GIT_COMMITTER_EMAIL", "orange-bot@users.noreply.github.com");
    }

    @Test
    void passEnvCannotLeakSecrets() {
        // Defence in depth: even a misconfigured pass-env never forwards credentials.
        List<String> misconfigured = List.of("PATH", "ANTHROPIC_API_KEY", "LITELLM_MASTER_KEY", "ORANGE_GITHUB_TOKEN",
                "GITHUB_TOKEN", "GH_TOKEN", "GITHUB_WEBHOOK_SECRET", "CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_AUTH_TOKEN");
        Map<String, String> host = Map.of("PATH", "/bin", "ANTHROPIC_API_KEY", "z", "LITELLM_MASTER_KEY", "y",
                "ORANGE_GITHUB_TOKEN", "x", "GITHUB_TOKEN", "g", "GH_TOKEN", "h", "GITHUB_WEBHOOK_SECRET", "w",
                "CLAUDE_CODE_OAUTH_TOKEN", "host-plan-token", "ANTHROPIC_AUTH_TOKEN", "host-auth");

        Map<String, String> gatewayEnv = ClaudeCodeCommand.env(
                plain(new Credentials.Gateway("http://localhost:4000", "sk-run")), host, misconfigured, Path.of("/c"));
        assertThat(gatewayEnv).doesNotContainKeys("ANTHROPIC_API_KEY", "LITELLM_MASTER_KEY", "ORANGE_GITHUB_TOKEN",
                "GITHUB_TOKEN", "GH_TOKEN", "GITHUB_WEBHOOK_SECRET", "CLAUDE_CODE_OAUTH_TOKEN");
        assertThat(gatewayEnv).containsEntry("ANTHROPIC_AUTH_TOKEN", "sk-run");

        Map<String, String> subEnv = ClaudeCodeCommand.env(
                plain(new Credentials.Subscription("run-token")), host, misconfigured, Path.of("/c"));
        assertThat(subEnv).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "run-token");
        assertThat(subEnv).doesNotContainKeys("ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY");
    }

    @Test
    void subscriptionEnv() {
        Map<String, String> env = ClaudeCodeCommand.env(plain(new Credentials.Subscription("tok")),
                Map.of("PATH", "/bin"), PASS_ENV, Path.of("/c"));
        assertThat(env).containsEntry("CLAUDE_CODE_OAUTH_TOKEN", "tok");
        assertThat(env).doesNotContainKeys("ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_API_KEY");
    }

    @Test
    void gatewayEnv() {
        Map<String, String> env = ClaudeCodeCommand.env(
                plain(new Credentials.Gateway("http://localhost:4000", "sk-1")),
                Map.of("PATH", "/bin"), PASS_ENV, Path.of("/c"));
        assertThat(env)
                .containsEntry("ANTHROPIC_BASE_URL", "http://localhost:4000")
                .containsEntry("ANTHROPIC_AUTH_TOKEN", "sk-1")
                .containsEntry("ENABLE_TOOL_SEARCH", "true");
        assertThat(env).doesNotContainKeys("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY");
    }

    @Test
    void credentialsToStringRedacts() {
        assertThat(new Credentials.Subscription("tok-secret").toString()).doesNotContain("tok-secret");
        String gw = new Credentials.Gateway("http://localhost:4000", "sk-1secret").toString();
        assertThat(gw).doesNotContain("sk-1secret").contains("http://localhost:4000");
        // A RunSpec printed in a log line must not leak the credential either.
        assertThat(plain(new Credentials.Subscription("tok-secret")).toString()).doesNotContain("tok-secret");
    }
}
