package com.ai.orange.repo;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A GitHub repository, as {@code owner/name} (remote-first spec §5): the identity of a project
 * repo. A laptop checkout and orange's mirror of the same repository have the same key, whatever
 * their paths. GitHub compares names without case, so keys are lowercased.
 */
public record RepoKey(String owner, String name) {

    private static final Pattern PART = Pattern.compile("[A-Za-z0-9._-]+");

    public RepoKey {
        owner = part(owner, "owner");
        name = part(name, "name");
    }

    /** {@code owner/name}, as a user or a plan names it. @throws IllegalArgumentException when it is not one */
    public static RepoKey of(String ownerSlashName) {
        if (ownerSlashName == null) throw new IllegalArgumentException("a repo is owner/name");
        String[] parts = ownerSlashName.strip().split("/", -1);
        if (parts.length != 2) throw new IllegalArgumentException("a repo is owner/name, not \"" + ownerSlashName + "\"");
        return new RepoKey(parts[0], parts[1]);
    }

    /**
     * The key of a remote URL when it names a repository on GitHub: {@code https://github.com/o/n},
     * {@code git@github.com:o/n}, {@code ssh://git@github.com/o/n} (each with or without
     * {@code .git} or a trailing slash), or any URL under {@code githubUrl}, which tests point at a
     * directory of bare repositories.
     */
    public static Optional<RepoKey> parse(String remoteUrl, String githubUrl) {
        if (remoteUrl == null || remoteUrl.isBlank()) return Optional.empty();
        String url = remoteUrl.strip();
        String path = null;
        String base = withSlash(githubUrl);
        if (base != null && url.startsWith(base)) {
            path = url.substring(base.length());
        } else if (base != null && base.startsWith("file://") && url.startsWith(base.substring("file://".length()))) {
            path = url.substring(base.length() - "file://".length());
        } else {
            String lower = url.toLowerCase(Locale.ROOT);
            for (String prefix : new String[] {"https://github.com/", "http://github.com/", "git@github.com:",
                    "ssh://git@github.com/"}) {
                if (lower.startsWith(prefix)) {
                    path = url.substring(prefix.length());
                    break;
                }
            }
            if (path == null && lower.startsWith("ssh://git@github.com:")) {
                String rest = url.substring("ssh://git@github.com:".length());
                int slash = rest.indexOf('/');
                if (slash > 0 && rest.substring(0, slash).chars().allMatch(Character::isDigit)) path = rest.substring(slash + 1);
            }
        }
        if (path == null) return Optional.empty();
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        if (path.endsWith(".git")) path = path.substring(0, path.length() - ".git".length());
        try {
            return Optional.of(of(path));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Where orange clones and pushes this repo: {@code <githubUrl>/<owner>/<name>.git}. */
    public String cloneUrl(String githubUrl) {
        return withSlash(githubUrl) + owner + "/" + name + ".git";
    }

    @Override
    public String toString() {
        return owner + "/" + name;
    }

    private static String part(String value, String what) {
        if (value == null || !PART.matcher(value).matches() || value.startsWith("-") || value.equals(".")
                || value.equals("..")) {
            throw new IllegalArgumentException("\"" + value + "\" is not a GitHub " + what);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String withSlash(String url) {
        if (url == null || url.isBlank()) return null;
        String u = url.strip();
        return u.endsWith("/") ? u : u + "/";
    }
}
