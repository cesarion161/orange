package com.ai.orange.worktree;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitAuthTest {

    static final String SECRET = "s3cr3t-x";

    @Test
    void theHelperHandsGitTheTokenFromItsEnvironment(@TempDir Path dir) throws Exception {
        Git.run(dir, Git.QUICK, "init", "-q");
        GitAuth auth = GitAuth.token(SECRET);
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(auth.configArgs());
        cmd.addAll(List.of("credential", "fill"));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().putAll(auth.env());
        Process p = pb.start();
        p.getOutputStream().write("protocol=https\nhost=github.com\n\n".getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        assertThat(out).contains("username=x-access-token").contains("password=" + SECRET);
    }

    @Test
    void theTokenIsInNoArgv() {
        GitAuth auth = GitAuth.token(SECRET);
        assertThat(auth.configArgs()).noneMatch(a -> a.contains(SECRET));
        assertThat(auth.toString()).doesNotContain(SECRET);
        assertThat(auth.env()).containsEntry("GIT_TERMINAL_PROMPT", "0");
    }

    @Test
    void anonymousClearsTheHostsHelpersAndNeverPrompts() {
        GitAuth auth = GitAuth.anonymous();
        assertThat(auth.configArgs()).containsExactly("-c", "credential.helper=");
        assertThat(auth.env()).containsOnly(Map.entry("GIT_TERMINAL_PROMPT", "0"));
        assertThat(GitAuth.NONE.configArgs()).isEmpty();
        assertThat(GitAuth.NONE.env()).isEmpty();
    }

    @Test
    void authenticatedGitRunsWithTheHelper(@TempDir Path dir) {
        Git.run(dir, Git.QUICK, "init", "-q");
        // The helper is configured for this one command, and git reports it as the helper list.
        String helpers = Git.run(dir, Git.QUICK, GitAuth.token(SECRET), "config", "--get-all", "credential.helper");
        assertThat(helpers).contains("ORANGE_GIT_SECRET").doesNotContain(SECRET);
    }

    @Test
    void gitNeverSeesTheTokensVariable() {
        assertThat(Git.environment(Map.of("ORANGE_GITHUB_TOKENS", "a=b", "PATH", "/bin")))
                .doesNotContainKey("ORANGE_GITHUB_TOKENS").containsKey("PATH");
    }
}
