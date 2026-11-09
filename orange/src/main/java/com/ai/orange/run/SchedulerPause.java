package com.ai.orange.run;

import static com.ai.orange.db.jooq.Tables.SETTINGS;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The coordinator-wide stop switch, kept in {@code settings} under {@code paused}. While paused,
 * the reactor queues nothing and the scheduler starts nothing. Failures that would recur at once
 * pause it (a plan limit until it resets, a rejected token until someone fixes it), so a broken
 * environment never turns into a loop of instantly failing runs.
 */
@Service
public class SchedulerPause {

    private static final Logger log = LoggerFactory.getLogger(SchedulerPause.class);
    static final String KEY = "paused";

    /**
     * @param until when the pause ends by itself; null means until someone resumes
     * @param since when the scheduler was first paused
     */
    public record Pause(String reason, OffsetDateTime until, OffsetDateTime since) {}

    private final DSLContext dsl;

    public SchedulerPause(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** The pause in effect, if any. A timed pause past its {@code until} is cleared. */
    public synchronized Optional<Pause> current() {
        Optional<Pause> pause = read();
        if (pause.isPresent() && pause.get().until() != null && !pause.get().until().isAfter(OffsetDateTime.now())) {
            clear();
            log.info("scheduler pause ({}) expired", pause.get().reason());
            return Optional.empty();
        }
        return pause;
    }

    /**
     * Pauses until {@code untilOrNull} (null: until resumed). An open-ended pause already in effect
     * stays, and a timed one is only ever extended, never shortened.
     */
    public synchronized void pause(String reason, OffsetDateTime untilOrNull) {
        Optional<Pause> existing = current();
        if (existing.isPresent()) {
            Pause e = existing.get();
            if (e.until() == null) return;
            if (untilOrNull != null && !untilOrNull.isAfter(e.until())) return;
        }
        OffsetDateTime since = existing.map(Pause::since).orElse(OffsetDateTime.now());
        write(new Pause(reason, untilOrNull, since));
        log.warn("scheduler paused: {} (until {})", reason, untilOrNull == null ? "resumed" : untilOrNull);
    }

    public synchronized void resume() {
        if (clear() > 0) log.info("scheduler resumed");
    }

    private int clear() {
        return dsl.deleteFrom(SETTINGS).where(SETTINGS.KEY.eq(KEY)).execute();
    }

    private Optional<Pause> read() {
        return dsl.select(SETTINGS.VALUE).from(SETTINGS).where(SETTINGS.KEY.eq(KEY))
                .fetchOptional(SETTINGS.VALUE)
                .flatMap(SchedulerPause::parse);
    }

    private void write(Pause pause) {
        ObjectNode node = Json.mapper().createObjectNode().put("reason", pause.reason());
        if (pause.until() == null) node.putNull("until");
        else node.put("until", pause.until().toString());
        node.put("since", pause.since().toString());
        JSONB value = JSONB.valueOf(node.toString());
        dsl.insertInto(SETTINGS)
                .set(SETTINGS.KEY, KEY)
                .set(SETTINGS.VALUE, value)
                .onConflict(SETTINGS.KEY)
                .doUpdate()
                .set(SETTINGS.VALUE, value)
                .execute();
    }

    private static Optional<Pause> parse(JSONB value) {
        try {
            JsonNode node = Json.mapper().readTree(value.data());
            String until = node.path("until").isTextual() ? node.path("until").asText() : null;
            return Optional.of(new Pause(
                    node.path("reason").asText(""),
                    until == null ? null : OffsetDateTime.parse(until),
                    OffsetDateTime.parse(node.path("since").asText())));
        } catch (Exception e) {
            log.warn("unreadable scheduler pause setting; treating it as paused until resumed: {}", e.getMessage());
            return Optional.of(new Pause("unreadable_setting", null, OffsetDateTime.now()));
        }
    }
}
