package com.ai.orange.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Quiet is measured against the project's own rhythm, or its cadence when it has one. */
class QuietTest {

    static final LocalDate TODAY = LocalDate.of(2026, 11, 10);

    static List<LocalDate> daysAgo(int... ago) {
        List<LocalDate> days = new ArrayList<>();
        for (int n : ago) days.add(TODAY.minusDays(n));
        return days;
    }

    static JsonNode json(String raw) throws Exception {
        return Json.mapper().readTree(raw);
    }

    @Test
    void withACadenceQuietIsSilenceLongerThanIt() {
        Quiet quiet = Quiet.of(daysAgo(8), TODAY, 7);
        assertThat(quiet.verdict()).isEqualTo(Quiet.QUIET);
        assertThat(quiet.quiet()).isTrue();
        assertThat(quiet.daysSince()).isEqualTo(8);
        assertThat(quiet.cadenceDays()).isEqualTo(7);
        assertThat(quiet.activeDays()).isEqualTo(1);
        assertThat(quiet.usualGap()).isNull();
        assertThat(quiet.longestGap()).isNull();

        Quiet normal = Quiet.of(daysAgo(7), TODAY, 7);
        assertThat(normal.verdict()).isEqualTo(Quiet.NORMAL);
        assertThat(normal.quiet()).isFalse();
    }

    @Test
    void twoActiveDaysAreTooFewToJudge() {
        Quiet quiet = Quiet.of(daysAgo(40, 30), TODAY, null);
        assertThat(quiet.verdict()).isEqualTo(Quiet.UNKNOWN);
        assertThat(quiet.activeDays()).isEqualTo(2);
        assertThat(quiet.daysSince()).isEqualTo(30);
        assertThat(quiet.usualGap()).isEqualTo(10.0);
        assertThat(quiet.longestGap()).isEqualTo(10);
    }

    @Test
    void aDailyProjectIsQuietAfterAWeekOfNothing() {
        // Gaps of one day: the threshold is twice the usual gap, 2 days.
        Quiet quiet = Quiet.of(daysAgo(10, 9, 8, 7), TODAY, null);
        assertThat(quiet.verdict()).isEqualTo(Quiet.QUIET);
        assertThat(quiet.usualGap()).isEqualTo(1.0);
        assertThat(quiet.longestGap()).isEqualTo(1);
        assertThat(quiet.daysSince()).isEqualTo(7);
        assertThat(quiet.lastActiveDay()).isEqualTo("2026-11-03");

        assertThat(Quiet.of(daysAgo(5, 4, 3, 2), TODAY, null).verdict()).as("2 days is not more than 2")
                .isEqualTo(Quiet.NORMAL);
        assertThat(Quiet.of(daysAgo(6, 5, 4, 3), TODAY, null).verdict()).isEqualTo(Quiet.QUIET);
    }

    @Test
    void aWeekdayRhythmSurvivesAWeekendAndNotMuchMore() {
        // Monday to Friday for three weeks: the longest gap, Friday to Monday, is 3 days.
        LocalDate friday = LocalDate.of(2026, 11, 6);
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = friday.minusDays(18); !d.isAfter(friday); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY) days.add(d);
        }
        assertThat(days).hasSize(15);

        Quiet monday = Quiet.of(days, friday.plusDays(3), null);
        assertThat(monday.verdict()).isEqualTo(Quiet.NORMAL);
        assertThat(monday.longestGap()).isEqualTo(3);
        assertThat(monday.usualGap()).isEqualTo(1.0);
        assertThat(Quiet.of(days, friday.plusDays(4), null).verdict()).isEqualTo(Quiet.QUIET);
    }

    @Test
    void theUsualGapOfAnEvenNumberOfGapsIsTheMeanOfTheMiddleTwo() {
        // Gaps 1, 2, 5, 10: the median is 3.5, and the longest gap sets the threshold.
        Quiet quiet = Quiet.of(daysAgo(28, 27, 25, 20, 10), TODAY, null);
        assertThat(quiet.usualGap()).isEqualTo(3.5);
        assertThat(quiet.longestGap()).isEqualTo(10);
        assertThat(quiet.verdict()).isEqualTo(Quiet.NORMAL);
        assertThat(Quiet.of(daysAgo(29, 28, 26, 21, 11), TODAY, null).verdict()).isEqualTo(Quiet.QUIET);
    }

    @Test
    void noActiveDayIsUnknown() {
        Quiet none = Quiet.of(List.of(), TODAY, null);
        assertThat(none.verdict()).isEqualTo(Quiet.UNKNOWN);
        assertThat(none.activeDays()).isZero();
        assertThat(none.daysSince()).isNull();
        assertThat(none.lastActiveDay()).isNull();
        // Also with a cadence: a project never worked on has not gone quiet.
        assertThat(Quiet.of(List.of(), TODAY, 7).verdict()).isEqualTo(Quiet.UNKNOWN);
        assertThat(Quiet.of(null, TODAY, null).verdict()).isEqualTo(Quiet.UNKNOWN);
    }

    @Test
    void daysAreCountedOnceInOrderAndNeverFromTheFuture() {
        List<LocalDate> days = daysAgo(1, 3, 3, 2, 1, -1, -4);
        Quiet quiet = Quiet.of(days, TODAY, null);
        assertThat(quiet.activeDays()).isEqualTo(3);
        assertThat(quiet.lastActiveDay()).isEqualTo("2026-11-09");
        assertThat(quiet.daysSince()).isEqualTo(1);
        assertThat(quiet.verdict()).isEqualTo(Quiet.NORMAL);
    }

    @Test
    void aCadenceIsAWordOrANumberOfDays() throws Exception {
        assertThat(Quiet.cadenceDays(json("\"Daily\""))).isEqualTo(1);
        assertThat(Quiet.cadenceDays(json("\"weekly\""))).isEqualTo(7);
        assertThat(Quiet.cadenceDays(json("\"monthly\""))).isEqualTo(30);
        assertThat(Quiet.cadenceDays(json("3"))).isEqualTo(3);
        assertThat(Quiet.cadenceDays(json("\"3\""))).isEqualTo(3);
        assertThat(Quiet.cadenceDays(json("0"))).isNull();
        assertThat(Quiet.cadenceDays(json("-2"))).isNull();
        assertThat(Quiet.cadenceDays(json("\"-2\""))).isNull();
        assertThat(Quiet.cadenceDays(json("2.5"))).isNull();
        assertThat(Quiet.cadenceDays(json("\"soon\""))).isNull();
        assertThat(Quiet.cadenceDays(json("{\"days\": 3}"))).isNull();
        assertThat(Quiet.cadenceDays(json("null"))).isNull();
        assertThat(Quiet.cadenceDays(null)).isNull();
    }
}
