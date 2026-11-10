package com.ai.orange.api;

import com.ai.orange.events.EventRepository;
import com.ai.orange.session.NeutralEvent;
import com.ai.orange.session.SessionEventHandler;
import com.ai.orange.session.SessionJudgement;
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
    /** One long-poll holds a request at most this long; the hook asks again while the state is pending. */
    static final int MAX_VERDICT_WAIT_SECONDS = 25;
    static final long VERDICT_POLL_MILLIS = 400;

    private final SessionRepository sessions;
    private final SessionJudgement judgement;

    public SessionController(EventRepository inbox, SessionRepository sessions, SessionJudgement judgement) {
        this.judgement = judgement;
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

    /**
     * Where a stop hook waits for orange's verdict on the stop it just reported as {@code event_id}
     * (project-space spec decision 2). Holds the request up to {@code wait} seconds, then answers
     * {@code {"state": "pending"}} (ask again), {@code "none"} (nothing is coming: let the session
     * stop) or {@code "verdict"} with its {@code kind} and, for {@code continue}, the {@code message}
     * to block the stop with, which the hook must acknowledge first ({@code POST /sessions/verdict/ack}).
     */
    @GetMapping("/verdict")
    public Map<String, Object> verdict(@RequestParam String harness, @RequestParam("session_id") String sessionId,
                                       @RequestParam("event_id") String eventId,
                                       @RequestParam(defaultValue = "20") int wait) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(Math.clamp(wait, 0, MAX_VERDICT_WAIT_SECONDS)).toNanos();
        while (true) {
            SessionJudgement.Poll poll = judgement.poll(harness, sessionId, eventId);
            if (poll.state() != SessionJudgement.Poll.State.PENDING || System.nanoTime() >= deadline) {
                Map<String, Object> out = new java.util.LinkedHashMap<>();
                out.put("state", poll.state().name().toLowerCase(java.util.Locale.ROOT));
                if (poll.kind() != null) out.put("kind", poll.kind());
                if (poll.message() != null) out.put("message", poll.message());
                return out;
            }
            Thread.sleep(VERDICT_POLL_MILLIS);
        }
    }

    /**
     * The stop hook's last step before it hands a {@code continue} to its session. {@code {"ok": true}}
     * makes the verdict delivered; on false the hook says nothing and the session stops.
     */
    @PostMapping("/verdict/ack")
    public Map<String, Object> acknowledge(@RequestParam String harness, @RequestParam("session_id") String sessionId,
                                           @RequestParam("event_id") String eventId) {
        return Map.of("ok", judgement.acknowledge(harness, sessionId, eventId));
    }

    /** Sessions, most recently heard from first. Ended ones only with {@code all=true}. */
    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "false") boolean all,
                                          @RequestParam(defaultValue = "50") int limit) {
        return sessions.findRecent(all, null, Math.clamp(limit, 1, 200)).stream().map(SpaceViews::of).toList();
    }
}
