package com.ai.orange.api;

import com.ai.orange.events.EventRepository;
import com.ai.orange.session.NeutralEvent;
import com.ai.orange.session.SessionEventHandler;
import com.ai.orange.session.SessionRepository;
import com.ai.orange.json.Json;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.util.List;
import java.util.Map;
import org.jooq.JSONB;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where session reporters post ({@code POST /sessions/events}) and the sessions orange follows.
 * An event is only validated and stored here; the inbox processor applies it, so a reporter's
 * hook never waits on orange's rules and a redelivered event is stored once.
 */
@RestController
@RequestMapping("/sessions")
public class SessionController {

    /** A stored event's excerpts are bounded; a reporter that sends more is cut off here. */
    static final int MAX_BODY_CHARS = 64_000;

    private final EventRepository inbox;
    private final SessionRepository sessions;

    public SessionController(EventRepository inbox, SessionRepository sessions) {
        this.inbox = inbox;
        this.sessions = sessions;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, Object>> event(@RequestBody String json) {
        if (json.length() > MAX_BODY_CHARS) {
            return ResponseEntity.status(413).body(Map.of("error", "event is larger than " + MAX_BODY_CHARS + " characters"));
        }
        NeutralEvent event;
        try {
            event = NeutralEvent.parse(Json.mapper().readTree(json));
        } catch (JsonProcessingException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "the event is not valid JSON"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        boolean stored = inbox.insert(SessionEventHandler.SOURCE, event.type(), null,
                SessionEventHandler.SOURCE + ":" + event.eventId(), JSONB.valueOf(json));
        return ResponseEntity.accepted().body(Map.of("stored", stored));
    }

    /** Sessions, most recently heard from first. Ended ones only with {@code all=true}. */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean all,
                                          @RequestParam(defaultValue = "50") int limit) {
        return sessions.findRecent(all, null, Math.clamp(limit, 1, 200)).stream().map(SpaceViews::of).toList();
    }
}
