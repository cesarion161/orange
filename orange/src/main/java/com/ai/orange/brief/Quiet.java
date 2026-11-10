package com.ai.orange.brief;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

/**
 * Whether a project has gone quiet against its own rhythm (project-space spec §7), as a pure rule
 * with no fixed threshold. An active day is a day with a prompt or a stop. With a {@code cadence}
 * convention the project is quiet when silent for longer than it; without one, when it has enough
 * history and has been silent for longer than twice its usual gap and its longest gap.
 *
 * @param verdict       {@code quiet}, {@code normal}, or {@code unknown} (too new to judge)
 * @param activeDays    how many days of the history had a prompt or a stop
 * @param lastActiveDay the latest of them (ISO), null when there is none
 * @param daysSince     days from that day to today
 * @param usualGap      the median number of days between two consecutive active days
 * @param longestGap    the longest such gap
 * @param cadenceDays   the project's {@code cadence} in days, null when it has none
 */
public record Quiet(String verdict, int activeDays, String lastActiveDay, Integer daysSince, Double usualGap,
                    Integer longestGap, Integer cadenceDays) {

    public static final String QUIET = "quiet";
    public static final String NORMAL = "normal";
    public static final String UNKNOWN = "unknown";
    /** The convention that says how often a project is worked on. */
    public static final String CADENCE = "cadence";

    /** Below this many active days a project has no rhythm to measure against. */
    static final int MIN_ACTIVE_DAYS = 3;

    public boolean quiet() {
        return QUIET.equals(verdict);
    }

    /**
     * A {@code cadence} value in days: {@code daily} 1, {@code weekly} 7, {@code monthly} 30, or a
     * positive number of days; null for anything else.
     */
    public static Integer cadenceDays(JsonNode value) {
        if (value == null) return null;
        if (value.isIntegralNumber()) return value.canConvertToInt() && value.asInt() > 0 ? value.asInt() : null;
        if (!value.isTextual()) return null;
        String text = value.asText().strip().toLowerCase(Locale.ROOT);
        switch (text) {
            case "daily": return 1;
            case "weekly": return 7;
            case "monthly": return 30;
            default:
        }
        try {
            int days = Integer.parseInt(text);
            return days > 0 ? days : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** The verdict for a project active on {@code activeDays} (days after {@code today} are ignored). */
    public static Quiet of(Collection<LocalDate> activeDays, LocalDate today, Integer cadenceDays) {
        TreeSet<LocalDate> sorted = new TreeSet<>();
        if (activeDays != null) {
            for (LocalDate day : activeDays) if (day != null && !day.isAfter(today)) sorted.add(day);
        }
        if (sorted.isEmpty()) return new Quiet(UNKNOWN, 0, null, null, null, null, cadenceDays);

        List<Integer> gaps = new ArrayList<>();
        LocalDate previous = null;
        for (LocalDate day : sorted) {
            if (previous != null) gaps.add((int) ChronoUnit.DAYS.between(previous, day));
            previous = day;
        }
        LocalDate last = sorted.last();
        int since = (int) ChronoUnit.DAYS.between(last, today);
        Double usual = median(gaps);
        Integer longest = gaps.stream().max(Integer::compare).orElse(null);

        String verdict;
        if (cadenceDays != null) {
            verdict = since > cadenceDays ? QUIET : NORMAL;
        } else if (sorted.size() < MIN_ACTIVE_DAYS) {
            verdict = UNKNOWN;
        } else {
            verdict = since > Math.max(2 * usual, longest) ? QUIET : NORMAL;
        }
        return new Quiet(verdict, sorted.size(), last.toString(), since, usual, longest, cadenceDays);
    }

    /** The middle gap, or the mean of the middle two; null when there is no gap. */
    private static Double median(List<Integer> gaps) {
        if (gaps.isEmpty()) return null;
        List<Integer> sorted = gaps.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? (double) sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2.0;
    }
}
