package com.ai.orange.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.json.Json;
import java.time.Duration;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Push discipline: urgency, the batch window and quiet hours. */
class PushPlannerTest {

    static final Duration WINDOW = Duration.ofMinutes(10);
    static final QuietHours NIGHT = new QuietHours(LocalTime.of(22, 0), LocalTime.of(8, 0));
    static final ZonedDateTime NOON = ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, ZoneId.of("UTC"));
    static final ZonedDateTime LATE = NOON.withHour(23);

    static AttentionItem item(String urgency) {
        return new AttentionItem(UUID.randomUUID(), null, null, null, "test", null, urgency, "t", "b", null,
                AttentionItem.OPEN, null, null, null, null, OffsetDateTime.now());
    }

    static Optional<OffsetDateTime> minutesBefore(ZonedDateTime now, int minutes) {
        return Optional.of(now.minusMinutes(minutes).toOffsetDateTime());
    }

    @Test
    void theFirstUrgentItemGoesAtOnce() {
        List<AttentionItem> items = List.of(item("urgent"));
        assertThat(PushPlanner.plan(items, Optional.empty(), NOON, NIGHT, WINDOW)).isEqualTo(items);
    }

    @Test
    void urgentItemsWaitForTheWindowAndThenGoTogether() {
        List<AttentionItem> items = List.of(item("urgent"), item("urgent"));
        assertThat(PushPlanner.plan(items, minutesBefore(NOON, 9), NOON, NIGHT, WINDOW)).isEmpty();
        assertThat(PushPlanner.plan(items, minutesBefore(NOON, 10), NOON, NIGHT, WINDOW)).isEqualTo(items);
    }

    @Test
    void criticalItemsIgnoreTheWindowAndQuietHours() {
        AttentionItem critical = item("critical");
        List<AttentionItem> items = List.of(item("urgent"), critical);
        assertThat(PushPlanner.plan(items, minutesBefore(NOON, 1), NOON, NIGHT, WINDOW)).containsExactly(critical);
        assertThat(PushPlanner.plan(items, Optional.empty(), LATE, NIGHT, WINDOW)).containsExactly(critical);
    }

    @Test
    void urgentItemsAreHeldInQuietHours() {
        assertThat(PushPlanner.plan(List.of(item("urgent")), Optional.empty(), LATE, NIGHT, WINDOW)).isEmpty();
        assertThat(PushPlanner.plan(List.of(item("urgent")), Optional.empty(), NOON.withHour(7).withMinute(59), NIGHT,
                WINDOW)).isEmpty();
        assertThat(PushPlanner.plan(List.of(item("urgent")), Optional.empty(), NOON.withHour(8), NIGHT, WINDOW)).hasSize(1);
    }

    @Test
    void quietHoursMayWrapMidnightOrNot() {
        assertThat(NIGHT.contains(LocalTime.of(22, 0))).isTrue();
        assertThat(NIGHT.contains(LocalTime.of(3, 0))).isTrue();
        assertThat(NIGHT.contains(LocalTime.of(8, 0))).isFalse();
        QuietHours lunch = new QuietHours(LocalTime.of(12, 0), LocalTime.of(13, 0));
        assertThat(lunch.contains(LocalTime.of(12, 30))).isTrue();
        assertThat(lunch.contains(LocalTime.of(13, 0))).isFalse();
        assertThat(QuietHours.NONE.contains(LocalTime.MIDNIGHT)).isFalse();
    }

    @Test
    void quietHoursComeFromTheConvention() throws Exception {
        assertThat(QuietHours.of(Json.mapper().readTree("[\"22:00\",\"08:00\"]"))).isEqualTo(NIGHT);
        assertThat(QuietHours.of(Json.mapper().readTree("[]"))).isEqualTo(QuietHours.NONE);
        assertThat(QuietHours.of(Json.mapper().readTree("[\"late\",\"early\"]"))).isEqualTo(QuietHours.NONE);
        assertThat(QuietHours.of(null)).isEqualTo(QuietHours.NONE);
    }
}
