package com.ai.orange.github;

/**
 * Outcome of opening a PR.
 *
 * @param repo     {@code owner/name} the PR was opened against
 * @param number   GitHub PR number (unique within {@code repo})
 * @param htmlUrl  human-readable PR URL
 */
public record PrInfo(String repo, int number, String htmlUrl) {
}
