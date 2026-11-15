package com.ai.orange.repo;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where orange keeps its mirrors of GitHub repos and how it reaches GitHub (remote-first spec §7.1,
 * builder's call 6).
 *
 * @param root      one bare mirror per repo, at {@code <root>/<owner>/<name>.git}; default {@code ~/.orange/repos}
 * @param githubUrl what {@code owner/name} is cloned from; tests point it at a directory of bare repos
 * @param tokens    a fine-grained personal access token per owner, {@code owner=token[,owner=token]}. They
 *                  reach git only through a credential helper's environment, never an argv, a URL or a file.
 */
@ConfigurationProperties(prefix = "orange.repos")
public record RepoProperties(String root, @DefaultValue("https://github.com/") String githubUrl,
                             @DefaultValue("") String tokens) {

    public RepoProperties {
        if (root == null || root.isBlank()) {
            root = Path.of(System.getProperty("user.home"), ".orange", "repos").toString();
        }
        if (githubUrl == null || githubUrl.isBlank()) githubUrl = "https://github.com/";
        if (tokens == null) tokens = "";
    }

    /** The token for repos of {@code owner}, if one is configured. */
    public Optional<String> tokenFor(String owner) {
        if (owner == null) return Optional.empty();
        for (String entry : tokens.split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0) continue;
            String o = entry.substring(0, eq).strip();
            String token = entry.substring(eq + 1).strip();
            if (o.toLowerCase(Locale.ROOT).equals(owner.toLowerCase(Locale.ROOT)) && !token.isEmpty()) {
                return Optional.of(token);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "RepoProperties[root=" + root + ", githubUrl=" + githubUrl + ", tokens=" + (tokens.isBlank() ? "none" : "(redacted)")
                + "]";
    }
}
