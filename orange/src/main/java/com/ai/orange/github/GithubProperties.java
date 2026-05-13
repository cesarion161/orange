package com.ai.orange.github;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Single source of truth for GitHub-related orchestrator config. Replaces the
 * older {@code GithubWebhookProperties} (which only held the HMAC bits).
 *
 * @param token             personal access token / app token used to push and open PRs
 * @param repo              {@code owner/name} of the target repository
 * @param baseBranch        branch PRs are opened against
 * @param defaultReviewers  GitHub usernames to request a review from on every PR
 * @param mergeWaitTimeout  how long the workflow waits for a merge before failing the task
 * @param webhookSecret     HMAC shared secret used by {@code GithubWebhookHmacFilter}
 * @param webhookPath       URL path the HMAC filter and webhook controller bind to
 */
@ConfigurationProperties(prefix = "orange.github")
public record GithubProperties(
        @DefaultValue("") String token,
        @DefaultValue("") String repo,
        @DefaultValue("main") String baseBranch,
        @DefaultValue({}) List<String> defaultReviewers,
        @DefaultValue("7d") Duration mergeWaitTimeout,
        @DefaultValue("") String webhookSecret,
        @DefaultValue("/webhooks/github") String webhookPath) {

    public boolean isPrCreationConfigured() {
        return !token.isBlank() && !repo.isBlank();
    }
}
