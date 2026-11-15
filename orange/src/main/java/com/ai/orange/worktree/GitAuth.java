package com.ai.orange.worktree;

import java.util.List;
import java.util.Map;

/**
 * How one git command authenticates to a remote (remote-first spec, builder's call 6). A token
 * reaches git only through the environment of that one git process, read by a credential helper
 * given on the command line: it is in no argv, no remote URL and no config file.
 */
public final class GitAuth {

    /** The variable the helper reads. */
    static final String SECRET_VAR = "ORANGE_GIT_SECRET";
    private static final String HELPER = "!f() { test \"$1\" = get || exit 0; echo username=x-access-token; "
            + "echo \"password=$" + SECRET_VAR + "\"; }; f";

    /** The host's own git credentials, as before repos had tokens (the legacy base repo). */
    public static final GitAuth NONE = new GitAuth(List.of(), Map.of(), "none");

    private final List<String> configArgs;
    private final Map<String, String> env;
    private final String label;

    private GitAuth(List<String> configArgs, Map<String, String> env, String label) {
        this.configArgs = configArgs;
        this.env = env;
        this.label = label;
    }

    /** No credentials at all: the host's helpers are cleared and git never prompts (a public repo reads fine). */
    public static GitAuth anonymous() {
        return new GitAuth(List.of("-c", "credential.helper="), Map.of("GIT_TERMINAL_PROMPT", "0"), "anonymous");
    }

    /** A personal access token, handed to git by the helper. */
    public static GitAuth token(String secret) {
        if (secret == null || secret.isBlank()) throw new IllegalArgumentException("a token is required");
        return new GitAuth(List.of("-c", "credential.helper=", "-c", "credential.helper=" + HELPER),
                Map.of(SECRET_VAR, secret, "GIT_TERMINAL_PROMPT", "0"), "token");
    }

    /** {@code -c} options that go before the git subcommand. */
    public List<String> configArgs() {
        return configArgs;
    }

    /** Variables added to git's environment, after orange's own secrets are removed from it. */
    public Map<String, String> env() {
        return env;
    }

    @Override
    public String toString() {
        return "GitAuth[" + label + "]";
    }
}
