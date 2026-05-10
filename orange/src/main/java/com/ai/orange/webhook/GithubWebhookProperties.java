package com.ai.orange.webhook;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "orange.github")
public record GithubWebhookProperties(
        @DefaultValue("") String webhookSecret,
        @DefaultValue("/webhooks/github") String webhookPath
) {
}
