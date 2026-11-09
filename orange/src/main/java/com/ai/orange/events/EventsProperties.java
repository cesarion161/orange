package com.ai.orange.events;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The events inbox ({@code orange.events}).
 *
 * @param processingEnabled whether stored events are processed (tests turn it off and drain by hand)
 * @param poll              how often to look for unprocessed events, besides {@code event_received}
 * @param maxAttempts       a failing event is tried this many times, then left with its last error
 */
@ConfigurationProperties(prefix = "orange.events")
public record EventsProperties(@DefaultValue("true") boolean processingEnabled,
                               @DefaultValue("5s") Duration poll,
                               @DefaultValue("5") int maxAttempts) {}
