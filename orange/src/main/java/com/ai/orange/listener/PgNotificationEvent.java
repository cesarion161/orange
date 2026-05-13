package com.ai.orange.listener;

/**
 * A Postgres {@code NOTIFY} payload, republished to Spring's
 * {@link org.springframework.context.ApplicationEventPublisher} by
 * {@link PgListenerService}. {@code channel} is the channel name
 * (e.g. {@code task_ready}); {@code payload} is the raw {@code pg_notify}
 * argument string.
 */
public record PgNotificationEvent(String channel, String payload) {
}
