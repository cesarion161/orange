package com.ai.orange.events;

import com.ai.orange.listener.PgNotificationEvent;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Works through the inbox: one event per transaction, oldest first. The first handler that
 * {@link EventHandler#handles handles} an event processes it; an event no handler wants is just
 * marked processed. A handler that throws is rolled back and the event tried again, until
 * {@code orange.events.max-attempts}, after which it is left with its last error.
 */
@Component
public class EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private final EventRepository events;
    private final List<EventHandler> handlers;
    private final EventsProperties props;
    private final TransactionTemplate tx;

    public EventProcessor(EventRepository events, List<EventHandler> handlers, EventsProperties props,
                          PlatformTransactionManager txManager) {
        this.events = events;
        this.handlers = List.copyOf(handlers);
        this.props = props;
        this.tx = new TransactionTemplate(txManager);
    }

    private record Attempt(InboxEvent event, RuntimeException error) {}

    /** Processes events until none with attempts left remain. One drain at a time keeps id order. */
    public synchronized void drain() {
        while (true) {
            Attempt attempt = tx.execute(status -> {
                Optional<InboxEvent> next = events.lockNext(props.maxAttempts());
                if (next.isEmpty()) return null;
                InboxEvent event = next.get();
                try {
                    Optional<EventHandler> handler = handlers.stream().filter(h -> h.handles(event)).findFirst();
                    if (handler.isPresent()) handler.get().handle(event);
                    else log.debug("no handler for {} event {} ({}); marking it processed", event.source(), event.id(),
                            event.type());
                    events.markProcessed(event.id());
                    return new Attempt(event, null);
                } catch (RuntimeException e) {
                    status.setRollbackOnly();
                    return new Attempt(event, e);
                }
            });
            if (attempt == null) return;
            if (attempt.error() != null) {
                log.warn("{} event {} ({}) failed, attempt {} of {}: {}", attempt.event().source(), attempt.event().id(),
                        attempt.event().type(), attempt.event().attempts() + 1, props.maxAttempts(),
                        attempt.error().toString());
                try {
                    events.markFailed(attempt.event().id(), attempt.error().toString());
                } catch (RuntimeException e) {
                    // Without the attempt counted, the same event would come straight back: stop here.
                    log.error("could not record the failure of event {}: {}", attempt.event().id(), e.toString());
                    return;
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${orange.events.poll:5s}")
    void poll() {
        if (props.processingEnabled()) drainSafely();
    }

    @EventListener
    void onNotification(PgNotificationEvent event) {
        if (props.processingEnabled() && "event_received".equals(event.channel())) {
            Thread.ofVirtual().name("events-drain").start(this::drainSafely);
        }
    }

    private void drainSafely() {
        try {
            drain();
        } catch (RuntimeException e) {
            log.warn("draining the events inbox failed: {}", e.toString());
        }
    }
}
