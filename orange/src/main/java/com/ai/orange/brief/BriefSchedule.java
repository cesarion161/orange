package com.ai.orange.brief;

import com.ai.orange.notify.QuietHours;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * When a brief is due (project-space spec §7), as a pure rule: at each brief time of the
 * {@code brief-times} convention, in the user's zone. A missed time is caught up only for a
 * while and only the latest one; a time inside quiet hours is skipped, not deferred.
 */
public final class BriefSchedule {

    private BriefSchedule() {}

    /** The brief times of a {@code brief-times} value, sorted and distinct; entries that are no time are skipped. */
    public static List<LocalTime> times(JsonNode value) {
        if (value == null || !value.isArray()) return List.of();
        TreeSet<LocalTime> times = new TreeSet<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual()) continue;
            try {
                times.add(LocalTime.parse(entry.asText()));
            } catch (DateTimeParseException e) {
                // Start-up validation refuses these; a value that slipped through costs one brief time.
            }
        }
        return List.copyOf(times);
    }

    /**
     * The brief time to write a brief for now: the latest one at or before {@code now}, when it is
     * at most {@code catchUp} old and outside quiet hours. One candidate, never a backlog.
     */
    public static Optional<ZonedDateTime> due(List<LocalTime> times, ZonedDateTime now, QuietHours quiet,
                                              Duration catchUp) {
        LocalDate today = now.toLocalDate();
        ZonedDateTime latest = null;
        for (LocalDate date : List.of(today.minusDays(1), today)) {
            for (LocalTime time : times) {
                ZonedDateTime slot = ZonedDateTime.of(date, time, now.getZone());
                if (!slot.isAfter(now) && (latest == null || slot.isAfter(latest))) latest = slot;
            }
        }
        if (latest == null || Duration.between(latest, now).compareTo(catchUp) > 0
                || quiet.contains(latest.toLocalTime())) {
            return Optional.empty();
        }
        return Optional.of(latest);
    }
}
