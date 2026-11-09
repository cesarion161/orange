package com.ai.orange.billing;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises Claude Code's "usage limit reached" failure on a subscription run. Such a run did not
 * fail on its merits: the plan's 5-hour or weekly allowance ran out, so it should be retried after
 * the reset rather than counted as a failed attempt.
 */
public final class PlanLimit {

    /** "reset at 5pm (Europe/Berlin)", "resets 3am", "resets 5:30pm (UTC)". */
    private static final Pattern RESET = Pattern.compile(
            "(?i)\\bresets?(?:\\s+at)?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\b(?:\\s*\\(([^)]+)\\))?");

    private PlanLimit() {}

    public static boolean matches(String text) {
        if (text == null || text.isBlank()) return false;
        String t = text.toLowerCase(Locale.ROOT);
        return t.contains("usage limit reached") || t.contains("limit will reset");
    }

    /**
     * When the limit named in {@code text} resets: the next occurrence of its clock time after
     * {@code now}, in the zone it names, or in {@code now}'s zone when it names none (or one Java
     * does not know). Empty when the text gives no reset time.
     */
    public static Optional<ZonedDateTime> resetsAt(String text, ZonedDateTime now) {
        if (text == null || now == null) return Optional.empty();
        Matcher m = RESET.matcher(text);
        if (!m.find()) return Optional.empty();

        int hour = Integer.parseInt(m.group(1));
        int minute = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (hour < 1 || hour > 12 || minute > 59) return Optional.empty();
        boolean pm = m.group(3).equalsIgnoreCase("pm");
        int hour24 = hour == 12 ? (pm ? 12 : 0) : (pm ? hour + 12 : hour);

        ZoneId zone = zone(m.group(4), now.getZone());
        ZonedDateTime local = now.withZoneSameInstant(zone);
        ZonedDateTime candidate = local.toLocalDate().atTime(LocalTime.of(hour24, minute)).atZone(zone);
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
        return Optional.of(candidate);
    }

    private static ZoneId zone(String name, ZoneId fallback) {
        if (name == null || name.isBlank()) return fallback;
        try {
            return ZoneId.of(name.strip(), ZoneId.SHORT_IDS);
        } catch (DateTimeException e) {
            return fallback;
        }
    }
}
