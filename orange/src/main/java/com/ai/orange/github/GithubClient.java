package com.ai.orange.github;

import java.io.IOException;
import java.util.List;

/**
 * Minimal GitHub surface area the orchestrator depends on. Hidden behind an
 * interface so tests can swap in a stub without invoking the real API. The
 * production implementation ({@link KohsukeGithubClient}) wraps the
 * {@code org.kohsuke:github-api} library.
 */
public interface GithubClient {

    /**
     * Opens a PR from {@code headBranch} into {@code baseBranch} on the
     * configured repository. Best-effort requests reviewers; errors there are
     * logged but do not fail the call.
     */
    PrInfo openPullRequest(String title, String body, String headBranch,
                           String baseBranch, List<String> reviewers) throws IOException;
}
