package com.ai.orange.session;

import com.ai.orange.events.EventHandler;
import com.ai.orange.events.InboxEvent;
import com.ai.orange.json.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Component;

/** Applies the session events the reporters stored in the inbox, in the order they arrived. */
@Component
public class SessionEventHandler implements EventHandler {

    public static final String SOURCE = "session";

    private final SessionService sessions;

    public SessionEventHandler(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean handles(InboxEvent event) {
        return SOURCE.equals(event.source());
    }

    @Override
    public void handle(InboxEvent event) {
        try {
            sessions.apply(NeutralEvent.parse(Json.mapper().readTree(event.payload().data())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored session event " + event.id() + " is not JSON", e);
        }
    }
}
