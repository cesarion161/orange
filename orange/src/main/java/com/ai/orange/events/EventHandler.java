package com.ai.orange.events;

/**
 * Processes one kind of stored event. A handler that throws has its transaction rolled back and
 * the event retried, up to {@code orange.events.max-attempts}.
 */
public interface EventHandler {

    boolean handles(InboxEvent event);

    void handle(InboxEvent event);
}
