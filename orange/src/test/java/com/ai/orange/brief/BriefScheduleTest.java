package com.ai.orange.brief;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.json.Json;
import com.ai.orange.notify.QuietHours;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** When a brief is due: the latest brief time, caught up for a while, never in quiet hours. */
class BriefScheduleTest {

    static final ZoneId UTC = ZoneId.of("UTC");
    static final List<LocalTime> TIMES = List.of(LocalTime.of(9, 0), LocalTime.of(14, 0));
    static final QuietHours NIGHT = new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0));
    static final Duration CATCH_UP = Duration.ofHours(2);

    static ZonedDateTime at(int hour, int minute) {
        return ZonedDateTime.of(2026, 11, 10, hour, minute, 0, 0, UTC);
    }

    @Test
    void nothingIsDueBeforeTheFirstBriefTime() {
        // Yesterday's 14:00 is the latest brief time, and it is far too old.
        assertThat(BriefSchedule.due(TIMES, at(8, 59), NIGHT, CATCH_UP)).isEmpty();
    }

    @Test
    void aBriefIsDueAtItsTimeAndForTheCatchUpAfterIt() {
        assertThat(BriefSchedule.due(TIMES, at(9, 0), NIGHT, CATCH_UP)).contains(at(9, 0));
        assertThat(BriefSchedule.due(TIMES, at(10, 59), NIGHT, CATCH_UP)).contains(at(9, 0));
        assertThat(BriefSchedule.due(TIMES, at(11, 0), NIGHT, CATCH_UP)).contains(at(9, 0));
        assertThat(BriefSchedule.due(TIMES, at(11, 1), NIGHT, CATCH_UP)).isEmpty();
    }

    @Test
    void onlyTheLatestMissedTimeIsDue() {
        // Both were missed: one candidate, no backlog.
        assertThat(BriefSchedule.due(TIMES, at(15, 0), NIGHT, CATCH_UP)).contains(at(14, 0));
        assertThat(BriefSchedule.due(TIMES, at(16, 30), NIGHT, Duration.ofHours(8))).contains(at(14, 0));
        // And never an earlier one in place of a latest that is too old or in quiet hours.
        assertThat(BriefSchedule.due(List.of(LocalTime.of(9, 0), LocalTime.of(10, 0)), at(12, 30), NIGHT,
                Duration.ofHours(1))).isEmpty();
        assertThat(BriefSchedule.due(List.of(LocalTime.of(21, 30), LocalTime.of(22, 30)), at(22, 40), NIGHT, CATCH_UP))
                .isEmpty();
    }

    @Test
    void aBriefTimeInQuietHoursIsSkipped() {
        List<LocalTime> early = List.of(LocalTime.of(7, 0));
        assertThat(BriefSchedule.due(early, at(7, 10), NIGHT, CATCH_UP)).isEmpty();
        // Skipped, not deferred: it does not come once quiet hours are over.
        assertThat(BriefSchedule.due(early, at(8, 5), NIGHT, CATCH_UP)).isEmpty();
        assertThat(BriefSchedule.due(early, at(7, 10), QuietHours.NONE, CATCH_UP)).contains(at(7, 0));
    }

    @Test
    void aBriefTimeOfYesterdayIsCaughtUpAfterMidnight() {
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 11, 0, 15, 0, 0, UTC);
        assertThat(BriefSchedule.due(List.of(LocalTime.of(23, 30)), now, QuietHours.NONE, CATCH_UP))
                .contains(ZonedDateTime.of(2026, 11, 10, 23, 30, 0, 0, UTC));
    }

    @Test
    void theSlotIsInTheZoneOfNow() {
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        ZonedDateTime now = ZonedDateTime.of(2026, 11, 10, 9, 5, 0, 0, berlin);
        ZonedDateTime slot = BriefSchedule.due(TIMES, now, NIGHT, CATCH_UP).orElseThrow();
        assertThat(slot).isEqualTo(ZonedDateTime.of(2026, 11, 10, 9, 0, 0, 0, berlin));
        assertThat(slot.getZone()).isEqualTo(berlin);
        // 09:05 in Berlin is 08:05 UTC: the same instant read in UTC has no brief due yet.
        assertThat(BriefSchedule.due(TIMES, now.withZoneSameInstant(UTC), NIGHT, CATCH_UP)).isEmpty();
    }

    @Test
    void aBriefTimeTheClocksSkipComesAnHourLater() {
        // Europe/Berlin, 2027-03-28: 02:00 becomes 03:00, so there is no 02:30 that day.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        List<LocalTime> halfPastTwo = List.of(LocalTime.of(2, 30));
        ZonedDateTime moved = ZonedDateTime.of(2027, 3, 28, 3, 30, 0, 0, berlin);
        assertThat(moved.getOffset().getTotalSeconds()).as("summer time by then").isEqualTo(7200);

        assertThat(BriefSchedule.due(halfPastTwo, moved.minusMinutes(1), QuietHours.NONE, CATCH_UP)).isEmpty();
        assertThat(BriefSchedule.due(halfPastTwo, moved.minusMinutes(35), QuietHours.NONE, CATCH_UP))
                .as("01:55 winter time: yesterday's 02:30 is long past, today's has not come").isEmpty();
        assertThat(BriefSchedule.due(halfPastTwo, moved.plusMinutes(5), QuietHours.NONE, CATCH_UP))
                .hasValueSatisfying(slot -> assertThat(slot.toInstant()).isEqualTo(moved.toInstant()));
        // Quiet hours are asked about the time it moved to, not the one that was written down.
        assertThat(BriefSchedule.due(halfPastTwo, moved.plusMinutes(5),
                new QuietHours(LocalTime.of(3, 15), LocalTime.of(4, 0)), CATCH_UP)).isEmpty();
        assertThat(BriefSchedule.due(halfPastTwo, moved.plusMinutes(5),
                new QuietHours(LocalTime.of(2, 15), LocalTime.of(3, 0)), CATCH_UP)).isPresent();
    }

    @Test
    void aBriefTimeTheClocksRepeatIsDueOnce() {
        // Europe/Berlin, 2026-10-25: 03:00 becomes 02:00, so 02:30 comes twice. The first one is the brief time.
        ZoneId berlin = ZoneId.of("Europe/Berlin");
        List<LocalTime> halfPastTwo = List.of(LocalTime.of(2, 30));
        ZonedDateTime first = ZonedDateTime.of(2026, 10, 25, 2, 30, 0, 0, berlin);
        ZonedDateTime second = first.plusHours(1);
        assertThat(first.getOffset().getTotalSeconds()).as("still summer time").isEqualTo(7200);
        assertThat(second.toLocalTime()).as("the same time on the clock, an hour later").isEqualTo(LocalTime.of(2, 30));

        assertThat(BriefSchedule.due(halfPastTwo, first.plusMinutes(5), QuietHours.NONE, CATCH_UP))
                .hasValueSatisfying(slot -> assertThat(slot.toInstant()).isEqualTo(first.toInstant()));
        // The second 02:30 is the same brief time, caught up: one slot, so one brief.
        assertThat(BriefSchedule.due(halfPastTwo, second.plusMinutes(5), QuietHours.NONE, CATCH_UP))
                .hasValueSatisfying(slot -> assertThat(slot.toInstant()).isEqualTo(first.toInstant()));
        assertThat(BriefSchedule.due(halfPastTwo, second.plusMinutes(5), QuietHours.NONE, Duration.ofMinutes(30)))
                .as("and no brief time of its own").isEmpty();
    }

    @Test
    void noTimesMeanNoBrief() {
        assertThat(BriefSchedule.due(List.of(), at(9, 0), NIGHT, CATCH_UP)).isEmpty();
    }

    @Test
    void timesAreReadSortedDistinctAndWithoutWhatIsNoTime() throws Exception {
        assertThat(BriefSchedule.times(Json.mapper().readTree(
                "[\"14:00\", \"09:00\", \"9:00\", \"24:00\", \"14:00\", 7, null, \"09:30:00\"]")))
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(14, 0));
        assertThat(BriefSchedule.times(null)).isEmpty();
        assertThat(BriefSchedule.times(Json.mapper().readTree("\"09:00\""))).isEmpty();
        assertThat(BriefSchedule.times(Json.mapper().readTree("{\"at\": \"09:00\"}"))).isEmpty();
        assertThat(BriefSchedule.times(Json.mapper().readTree("[]"))).isEmpty();
    }
}
