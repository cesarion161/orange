package com.ai.orange.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Merge-mode integration ({@code orange.integration}; project-space spec §8 {@code integration: merge}).
 *
 * @param remotePush push the base after integrating when the base repo has an {@code origin}
 *                   remote (the default). Off: integrate onto the local base branch only.
 */
@ConfigurationProperties(prefix = "orange.integration")
public record IntegrationProperties(@DefaultValue("true") boolean remotePush) {}
