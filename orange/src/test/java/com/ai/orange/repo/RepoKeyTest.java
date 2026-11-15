package com.ai.orange.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RepoKeyTest {

    static final String GITHUB = "https://github.com/";

    @Test
    void githubUrlShapesAreOneKey() {
        for (String url : List.of("https://github.com/Owner/Repo.git", "git@github.com:owner/repo",
                "ssh://git@github.com/owner/repo/", "https://github.com/owner/repo", "ssh://git@github.com:22/owner/repo.git",
                "http://github.com/owner/repo.git")) {
            Optional<RepoKey> key = RepoKey.parse(url, GITHUB);
            assertThat(key).as(url).contains(RepoKey.of("owner/repo"));
            assertThat(key.get().toString()).isEqualTo("owner/repo");
        }
    }

    @Test
    void aNonGithubRemoteIsNoRepo() {
        assertThat(RepoKey.parse("https://gitlab.com/o/n.git", GITHUB)).isEmpty();
        assertThat(RepoKey.parse("/srv/git/o/n.git", GITHUB)).isEmpty();
        assertThat(RepoKey.parse("https://github.com/o", GITHUB)).isEmpty();
        assertThat(RepoKey.parse("https://github.com/o/n/tree/main", GITHUB)).isEmpty();
        assertThat(RepoKey.parse(null, GITHUB)).isEmpty();
        assertThat(RepoKey.parse("", GITHUB)).isEmpty();
    }

    @Test
    void aFileGithubUrlStandsInForGithub() {
        String base = "file:///tmp/gh/";
        assertThat(RepoKey.parse("file:///tmp/gh/a/b.git", base)).contains(RepoKey.of("a/b"));
        assertThat(RepoKey.parse("/tmp/gh/a/b.git", base)).contains(RepoKey.of("a/b"));
        assertThat(RepoKey.parse("/tmp/other/a/b.git", base)).isEmpty();
    }

    @Test
    void badNamesAreRefused() {
        for (String bad : List.of("../x", "-o/x", "o/x/y", "o", "o/..", "o/.", "o/x y", "/x", "o/")) {
            assertThatThrownBy(() -> RepoKey.of(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(RepoKey.parse("https://github.com/-o/x.git", GITHUB)).isEmpty();
    }

    @Test
    void cloneUrlJoinsTheBase() {
        assertThat(RepoKey.of("a/b").cloneUrl("https://github.com/")).isEqualTo("https://github.com/a/b.git");
        assertThat(RepoKey.of("a/b").cloneUrl("https://github.com")).isEqualTo("https://github.com/a/b.git");
    }

    @Test
    void tokensAreFoundByOwnerWithoutCaseAndNeverPrinted() {
        RepoProperties props = new RepoProperties(null, "https://github.com/", "Alice=t1, acme=t2");
        assertThat(props.tokenFor("alice")).contains("t1");
        assertThat(props.tokenFor("ACME")).contains("t2");
        assertThat(props.tokenFor("bob")).isEmpty();
        assertThat(props.toString()).doesNotContain("t1").doesNotContain("t2");
        assertThat(props.root()).endsWith("/.orange/repos");
    }
}
