package com.ai.orange.brief;

import static com.ai.orange.db.jooq.Tables.BRIEFS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/** The briefs table: one brief per brief time, the newest first, delivered once. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class BriefRepositoryIT {

    static final JSONB EMPTY = JSONB.valueOf("{}");

    @Autowired BriefRepository briefs;
    @Autowired DSLContext dsl;

    final List<UUID> inserted = new ArrayList<>();
    /** Far in the future and at a random minute, so nothing another test wrote is newer or in the way. */
    final OffsetDateTime base = OffsetDateTime.of(2100, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
            .plusMinutes(ThreadLocalRandom.current().nextInt(0, 500_000));

    @AfterEach
    void deleteWhatWasInserted() {
        dsl.deleteFrom(BRIEFS).where(BRIEFS.ID.in(inserted)).execute();
    }

    Optional<Brief> insert(OffsetDateTime slot, OffsetDateTime snapshotAt, boolean pushable) {
        Optional<Brief> brief = briefs.insert(slot, snapshotAt, EMPTY, null, null, null, pushable,
                "key-" + UUID.randomUUID());
        brief.ifPresent(b -> inserted.add(b.id()));
        return brief;
    }

    @Test
    void aBriefTimeHasOneBrief() {
        Brief first = insert(base, base.plusSeconds(5), true).orElseThrow();

        assertThat(first.scheduled()).isTrue();
        assertThat(first.slotAt().toInstant()).isEqualTo(base.toInstant());
        assertThat(first.snapshotAt().toInstant()).isEqualTo(base.plusSeconds(5).toInstant());
        assertThat(first.snapshot().data()).isEqualTo("{}");
        assertThat(first.content()).isNull();
        assertThat(first.pushable()).isTrue();
        assertThat(first.deliveredAt()).isNull();
        assertThat(first.createdAt()).isNotNull();
        assertThat(insert(base, base.plusSeconds(9), true)).as("the slot is taken").isEmpty();
        assertThat(briefs.findById(first.id())).contains(first);
        assertThat(briefs.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void aBriefThatWasAskedForAlwaysLands() {
        Brief one = insert(null, base, false).orElseThrow();
        Brief two = insert(null, base, false).orElseThrow();

        assertThat(one.scheduled()).isFalse();
        assertThat(two.id()).isNotEqualTo(one.id());
    }

    @Test
    void theContentNoteAndRunAreKept() {
        Brief brief = briefs.insert(null, base, JSONB.valueOf("{\"at\": \"x\"}"),
                JSONB.valueOf("{\"focus\": []}"), "nothing new since the last brief", null, false, "the-key")
                .orElseThrow();
        inserted.add(brief.id());

        Brief read = briefs.findById(brief.id()).orElseThrow();
        assertThat(read.snapshot().data()).contains("\"at\"");
        assertThat(read.content().data()).contains("focus");
        assertThat(read.note()).isEqualTo("nothing new since the last brief");
        assertThat(read.pageKey()).isEqualTo("the-key");
        assertThat(read.runId()).isNull();
    }

    @Test
    void theLatestScheduledBriefIgnoresThoseAskedFor() {
        Brief scheduled = insert(base, base.plusSeconds(1), false).orElseThrow();
        Brief asked = insert(null, base.plusHours(1), false).orElseThrow();

        assertThat(briefs.latest().orElseThrow().id()).isEqualTo(asked.id());
        assertThat(briefs.latestScheduled().orElseThrow().id()).isEqualTo(scheduled.id());
    }

    @Test
    void aSlotIsKnownOnceItHasABrief() {
        assertThat(briefs.existsForSlot(base)).isFalse();
        insert(base, base, false);
        assertThat(briefs.existsForSlot(base)).isTrue();
        // The same instant, written with another offset.
        assertThat(briefs.existsForSlot(base.withOffsetSameInstant(ZoneOffset.ofHours(2)))).isTrue();
        assertThat(briefs.existsForSlot(base.plusMinutes(1))).isFalse();
    }

    @Test
    void undeliveredAreThePushableScheduledOnesNewestFirst() {
        Brief older = insert(base, base, true).orElseThrow();
        Brief newer = insert(base.plusHours(5), base.plusHours(5), true).orElseThrow();
        Brief nothingNew = insert(base.plusHours(10), base.plusHours(10), false).orElseThrow();
        Brief asked = insert(null, base.plusHours(11), true).orElseThrow();
        Brief delivered = insert(base.plusHours(15), base.plusHours(15), true).orElseThrow();
        briefs.markDelivered(delivered.id(), OffsetDateTime.now());

        List<UUID> undelivered = briefs.findUndelivered().stream().map(Brief::id).filter(inserted::contains).toList();
        assertThat(undelivered).containsExactly(newer.id(), older.id())
                .doesNotContain(nothingNew.id(), asked.id(), delivered.id());
    }

    @Test
    void aBriefIsMarkedDeliveredOnce() {
        Brief brief = insert(base, base, true).orElseThrow();
        OffsetDateTime at = OffsetDateTime.now().truncatedTo(ChronoUnit.MILLIS);

        assertThat(briefs.markDelivered(brief.id(), at)).isTrue();
        assertThat(briefs.markDelivered(brief.id(), at.plusMinutes(1))).isFalse();
        assertThat(briefs.findById(brief.id()).orElseThrow().deliveredAt().toInstant()).isEqualTo(at.toInstant());
        assertThat(briefs.markDelivered(UUID.randomUUID(), at)).isFalse();
    }

    @Test
    void oldBriefsAreDeleted() {
        Brief old = insert(base, base, false).orElseThrow();
        Brief recent = insert(base.plusHours(5), base.plusHours(5), false).orElseThrow();
        // Older than anything another test could have written, so only this row goes.
        OffsetDateTime longAgo = OffsetDateTime.of(1990, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        dsl.update(BRIEFS).set(BRIEFS.CREATED_AT, longAgo).where(BRIEFS.ID.eq(old.id())).execute();

        assertThat(briefs.deleteOlderThan(longAgo.plusDays(1))).isEqualTo(1);
        assertThat(briefs.findById(old.id())).isEmpty();
        assertThat(briefs.findById(recent.id())).isPresent();
        assertThat(briefs.deleteOlderThan(longAgo.plusDays(1))).isZero();
    }
}
