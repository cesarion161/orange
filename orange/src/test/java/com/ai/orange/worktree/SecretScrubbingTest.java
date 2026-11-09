package com.ai.orange.worktree;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SecretScrubbingTest {

    @Test
    void orangesOwnSecretsAreDroppedFromGitsEnvironment() {
        Map<String, String> env = Git.environment(Map.of(
                "CLAUDE_CODE_OAUTH_TOKEN", "oauth",
                "LITELLM_MASTER_KEY", "master",
                "ANTHROPIC_API_KEY", "api",
                "GITHUB_WEBHOOK_SECRET", "hmac",
                "ORANGE_GITHUB_TOKEN", "orange-gh",
                "PATH", "/usr/bin",
                "HOME", "/home/me",
                "SSH_AUTH_SOCK", "/tmp/agent.sock",
                "GH_TOKEN", "host-git-credential"));

        assertThat(env).doesNotContainKeys("CLAUDE_CODE_OAUTH_TOKEN", "LITELLM_MASTER_KEY", "ANTHROPIC_API_KEY",
                "GITHUB_WEBHOOK_SECRET", "ORANGE_GITHUB_TOKEN");
        assertThat(env).as("the host's own git credentials still work")
                .containsEntry("PATH", "/usr/bin").containsEntry("HOME", "/home/me")
                .containsEntry("SSH_AUTH_SOCK", "/tmp/agent.sock").containsEntry("GH_TOKEN", "host-git-credential");
    }
}
