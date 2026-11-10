package com.ai.orange.notify;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

/**
 * The hours in which only critical items are pushed (the {@code quiet-hours} convention, e.g.
 * {@code ["22:00", "08:00"]}). A window may wrap midnight; equal ends mean no quiet hours.
 */
public record QuietHours(LocalTime from, LocalTime to) {

    public static final QuietHours NONE = new QuietHours(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT);

    /** Reads a two-element array of HH:mm strings; anything else means no quiet hours. */
    public static QuietHours of(JsonNode value) {
        if (value == null || !value.isArray() || value.size() != 2) return NONE;
        try {
            return new QuietHours(LocalTime.parse(value.get(0).asText()), LocalTime.parse(value.get(1).asText()));
        } catch (DateTimeParseException e) {
            return NONE;
        }
    }

    public boolean contains(LocalTime time) {
        if (from.equals(to)) return false;
        return from.isBefore(to)
                ? !time.isBefore(from) && time.isBefore(to)
                : !time.isBefore(from) || time.isBefore(to);
    }
}
