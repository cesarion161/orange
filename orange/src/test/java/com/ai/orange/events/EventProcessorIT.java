package com.ai.orange.events;

import static com.ai.orange.db.jooq.Tables.EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.db.jooq.tables.records.EventsRecord;
import com.ai.orange.task.Task;
import com.ai.orange.task.TaskDef;
import com.ai.orange.task.TaskRepository;
import com.ai.orange.task.TaskService;
import com.ai.orange.task.TaskStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/** Stored events are handled one per transaction, in id order; failures are retried, then parked. */
@SpringBootTest
@Import({TestcontainersConfiguration.class, EventProcessorIT.Handlers.class})
class EventProcessorIT {

    static final List<Long> SEEN = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class Handlers {
        @Bean
        EventHandler recordingHandler() {
            return new EventHandler() {
                @Override
                public boolean handles(InboxEvent e) {
                    return e.source().equals("test");
                }

                @Override
                public void handle(InboxEvent e) {
                    SEEN.add(e.id());
                }
            };
        }

        @Bean
        EventHandler explodingHandler() {
            return new EventHandler() {
                @Override
                public boolean handles(InboxEvent e) {
                    return e.source().equals("boom");
                }

                @Override
                public void handle(InboxEvent e) {
                    throw new IllegalStateException("handler exploded on " + e.id());
                }
            };
        }
    }

    @Autowired EventProcessor processor;
    @Autowired EventRepository events;
    @Autowired TaskService taskService;
    @Autowired TaskRepository tasks;
    @Autowired DSLContext dsl;

    @BeforeEach
    void clean() {
        dsl.deleteFrom(EVENTS).execute();
        SEEN.clear();
    }

    EventsRecord row(String deliveryId) {
        return dsl.selectFrom(EVENTS).where(EVENTS.DELIVERY_ID.eq(deliveryId)).fetchSingle();
    }

    boolean insert(String source, String type, String delivery, String payload) {
        return events.insert(source, type, "acme/widgets", delivery, JSONB.valueOf(payload));
    }

    @Test
    void eventsAreProcessedInIdOrder() {
        for (int i = 0; i < 5; i++) insert("test", "thing", "d-" + i, "{\"n\":" + i + "}");

        processor.drain();

        List<Long> ids = dsl.select(EVENTS.ID).from(EVENTS).orderBy(EVENTS.ID).fetch(EVENTS.ID);
        assertThat(SEEN).containsExactlyElementsOf(ids);
        assertThat(dsl.fetchCount(EVENTS, EVENTS.PROCESSED_AT.isNull())).isZero();
    }

    @Test
    void pullRequestEventsChangeNoTask() {
        Task task = taskService.createGraph(List.of(new TaskDef("evt-" + UUID.randomUUID(), "T", "x",
                "implementer", null, null)), List.of()).get(0);
        tasks.transitionStatus(task.id(), TaskStatus.READY, TaskStatus.RUNNING);
        tasks.transitionStatus(task.id(), TaskStatus.RUNNING, TaskStatus.VERIFYING);
        tasks.transitionStatus(task.id(), TaskStatus.VERIFYING, TaskStatus.VERIFIED);
        tasks.recordPublished(task.id(), "acme/widgets", "orange/" + task.id(), 7, "https://example/pull/7", null);
        insert("github", "pull_request", "gh-1", """
                {"action":"closed","repository":{"full_name":"acme/widgets"},"pull_request":{"number":7,"merged":true}}""");

        processor.drain();

        assertThat(tasks.findById(task.id()).orElseThrow().status()).as("merging through PRs is PR mode, not built")
                .isEqualTo(TaskStatus.VERIFIED);
        assertThat(row("gh-1").getProcessedAt()).isNotNull();
    }

    @Test
    void failingHandlerIsRetriedFiveTimesThenParked() {
        insert("boom", "thing", "boom-1", "{}");
        insert("test", "thing", "after-boom", "{}");

        processor.drain();

        EventsRecord parked = row("boom-1");
        assertThat(parked.getAttempts()).isEqualTo(5);
        assertThat(parked.getProcessedAt()).isNull();
        assertThat(parked.getLastError()).contains("handler exploded");
        assertThat(row("after-boom").getProcessedAt()).as("a parked event does not block the ones after it").isNotNull();

        processor.drain();
        assertThat(row("boom-1").getAttempts()).as("parked for good").isEqualTo(5);
    }

    @Test
    void unhandledEventsAreMarkedProcessed() {
        insert("nobody", "thing", "n-1", "{}");

        processor.drain();

        assertThat(row("n-1").getProcessedAt()).isNotNull();
        assertThat(row("n-1").getAttempts()).isZero();
    }

    @Test
    void aDuplicateDeliveryIsNotInsertedTwice() {
        assertThat(insert("test", "thing", "dup", "{}")).isTrue();
        assertThat(insert("test", "thing", "dup", "{}")).isFalse();
        assertThat(dsl.fetchCount(EVENTS)).isEqualTo(1);
    }
}
