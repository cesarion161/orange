package com.ai.orange.repo;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where orange keeps its mirrors of GitHub repos and how it reaches GitHub (remote-first spec §7.1,
 * builder's call 6).
 *
 * @param root      one bare mirror per repo, at {@code <root>/<owner>/<name>.git}; default {@code ~/.orange/repos}
 * @param githubUrl what {@code owner/name} is cloned from; tests point it at a directory of bare repos
 * @param tokens    a fine-grained personal access token per owner, {@code owner=token[,owner=token]}. They
 *                  reach git only through a credential helper's environment, never an argv, a URL or a file,
 *                  and GitHub's REST API only in a request's {@code Authorization} header ({@link GithubRepos}).
 * @param githubApiUrl GitHub's REST API, without a trailing slash, where {@code orange_project_create} makes
 *                  a repo; tests point it at a local server
 */
@ConfigurationProperties(prefix = "orange.repos")
public record RepoProperties(String root, @DefaultValue("https://github.com/") String githubUrl,
                             @DefaultValue("") String tokens,
                             @DefaultValue("https://api.github.com") String githubApiUrl) {

    public static final String GITHUB_API = "https://api.github.com";

    @ConstructorBinding
    public RepoProperties {
        if (root == null || root.isBlank()) {
            root = Path.of(System.getProperty("user.home"), ".orange", "repos").toString();
        }
        if (githubUrl == null || githubUrl.isBlank()) githubUrl = "https://github.com/";
        if (tokens == null) tokens = "";
        githubApiUrl = githubApiUrl == null || githubApiUrl.isBlank() ? GITHUB_API : githubApiUrl.strip();
        while (githubApiUrl.endsWith("/")) githubApiUrl = githubApiUrl.substring(0, githubApiUrl.length() - 1);
    }

    /** With GitHub's own REST API. */
    public RepoProperties(String root, String githubUrl, String tokens) {
        this(root, githubUrl, tokens, GITHUB_API);
    }

    /** The owners that have a token, in the order configured. */
    public List<String> owners() {
        List<String> owners = new ArrayList<>();
        for (String entry : tokens.split(",")) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || entry.substring(eq + 1).isBlank()) continue;
            String o = entry.substring(0, eq).strip();
            if (owners.stream().noneMatch(x -> x.equalsIgnoreCase(o))) owners.add(o);
        }
        return owners;
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
                + ", githubApiUrl=" + githubApiUrl + "]";
    }
}
