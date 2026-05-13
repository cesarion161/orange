package com.ai.orange.github;

import java.io.IOException;
import java.util.List;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Production {@link GithubClient}, wired to GitHub via {@code org.kohsuke:github-api}.
 * Always registered — but fails fast at call time if the orchestrator isn't
 * configured for PR creation ({@code orange.github.token} blank). Tests
 * override this with a {@code @Primary} stub bean so unit tests and dev runs
 * without real credentials don't try to phone home.
 */
@Component
public class KohsukeGithubClient implements GithubClient {

    private static final Logger log = LoggerFactory.getLogger(KohsukeGithubClient.class);

    private final GithubProperties props;

    public KohsukeGithubClient(GithubProperties props) {
        this.props = props;
    }

    @Override
    public PrInfo openPullRequest(String title, String body, String headBranch,
                                   String baseBranch, List<String> reviewers) throws IOException {
        if (!props.isPrCreationConfigured()) {
            throw new IOException("GitHub not configured: set orange.github.token and orange.github.repo");
        }
        GitHub gh = new GitHubBuilder().withOAuthToken(props.token()).build();
        GHRepository repo = gh.getRepository(props.repo());
        GHPullRequest pr = repo.createPullRequest(title, headBranch, baseBranch, body);
        if (reviewers != null && !reviewers.isEmpty()) {
            try {
                pr.requestReviewers(reviewers.stream().map(login -> {
                    try {
                        return gh.getUser(login);
                    } catch (IOException e) {
                        log.warn("could not resolve reviewer {}: {}", login, e.getMessage());
                        return null;
                    }
                }).filter(java.util.Objects::nonNull).toList());
            } catch (IOException e) {
                log.warn("could not request reviewers on PR #{}: {}", pr.getNumber(), e.getMessage());
            }
        }
        log.info("opened PR #{} on {}: {}", pr.getNumber(), props.repo(), pr.getHtmlUrl());
        return new PrInfo(props.repo(), pr.getNumber(), pr.getHtmlUrl().toString());
    }
}
