package com.ai.orange.brief;

import static com.ai.orange.brief.BriefFixtures.project;
import static com.ai.orange.brief.BriefFixtures.snapshot;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.notify.QuietHours;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Which brief goes to the phone: the newest one that is due, never a late one, never at night. */
class BriefDeliveryTest {

    static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 10, 10, 0, 0, 0, ZoneId.of("Europe/Berlin"));
    static final QuietHours NIGHT = new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0));
    static final Duration CATCH_UP = Duration.ofHours(2);

    /** A scheduled, pushable, undelivered brief for a brief time that long ago. */
    static Brief briefFrom(Duration ago) {
        return BriefFixtures.brief(NOW.minus(ago).toOffsetDateTime(), snapshot(project("alpha")).build().toJsonb(),
                null, null);
    }

    static Brief with(Brief b, OffsetDateTime slotAt, boolean pushable, OffsetDateTime deliveredAt) {
        return new Brief(b.id(), slotAt, b.snapshotAt(), b.snapshot(), b.content(), b.note(), b.runId(), pushable,
                b.pageKey(), deliveredAt, b.createdAt());
    }

    @Test
    void aBriefThatIsDueIsPicked() {
        Brief due = briefFrom(Duration.ofHours(1));
        assertThat(BriefDelivery.pick(List.of(due), NOW, NIGHT, CATCH_UP)).contains(due);
        assertThat(BriefDelivery.pick(List.of(), NOW, NIGHT, CATCH_UP)).isEmpty();
    }

    @Test
    void nothingGoesOutInQuietHours() {
        Brief due = briefFrom(Duration.ofMinutes(5));
        assertThat(BriefDelivery.pick(List.of(due), NOW.withHour(23), NIGHT, CATCH_UP)).isEmpty();
        assertThat(BriefDelivery.pick(List.of(due), NOW.withHour(7).withMinute(59), NIGHT, CATCH_UP)).isEmpty();
        assertThat(BriefDelivery.pick(List.of(due), NOW, QuietHours.NONE, CATCH_UP)).contains(due);
    }

    @Test
    void aLateBriefIsNotPushed() {
        Brief late = briefFrom(Duration.ofHours(3));
        Brief inTime = briefFrom(Duration.ofHours(1));
        assertThat(BriefDelivery.pick(List.of(late), NOW, NIGHT, CATCH_UP)).isEmpty();
        assertThat(BriefDelivery.pick(List.of(late, inTime), NOW, NIGHT, CATCH_UP)).contains(inTime);
        // Exactly the catch-up time ago still counts; a second more does not.
        assertThat(BriefDelivery.pick(List.of(briefFrom(CATCH_UP)), NOW, NIGHT, CATCH_UP)).isPresent();
        assertThat(BriefDelivery.pick(List.of(briefFrom(CATCH_UP.plusSeconds(1))), NOW, NIGHT, CATCH_UP)).isEmpty();
    }

    @Test
    void ofTwoTheNewerBriefTimeWins() {
        Brief older = briefFrom(Duration.ofMinutes(90));
        Brief newer = briefFrom(Duration.ofMinutes(10));
        assertThat(BriefDelivery.pick(List.of(older, newer), NOW, NIGHT, CATCH_UP)).contains(newer);
        assertThat(BriefDelivery.pick(List.of(newer, older), NOW, NIGHT, CATCH_UP)).contains(newer);
    }

    @Test
    void whatWasAskedForHasNothingNewOrWentOutIsNeverPicked() {
        Brief due = briefFrom(Duration.ofMinutes(10));
        Brief onDemand = with(due, null, true, null);
        Brief nothingNew = with(due, due.slotAt(), false, null);
        Brief delivered = with(due, due.slotAt(), true, NOW.minusMinutes(5).toOffsetDateTime());
        assertThat(BriefDelivery.pick(List.of(onDemand, nothingNew, delivered), NOW, NIGHT, CATCH_UP)).isEmpty();
        assertThat(BriefDelivery.pick(List.of(onDemand, nothingNew, delivered, due), NOW, NIGHT, CATCH_UP)).contains(due);
    }
}
