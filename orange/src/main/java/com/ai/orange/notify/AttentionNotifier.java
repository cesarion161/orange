package com.ai.orange.notify;

import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.listener.PgNotificationEvent;
import com.ai.orange.project.Conventions;
import java.time.ZonedDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes urgent and critical attention items to the user's phone, by {@link PushPlanner}'s
 * discipline. One push carries every item that is due. An item counts as pushed only once ntfy
 * took it, so a failed push is tried again at the next sweep.
 */
@Component
public class AttentionNotifier {

    private static final Logger log = LoggerFactory.getLogger(AttentionNotifier.class);
    /** ntfy cuts messages at 4096 bytes; stay well under it. */
    static final int MAX_MESSAGE = 1_500;

    private final AttentionRepository items;
    private final NtfyClient ntfy;
    private final NotifyProperties props;
    private final Conventions conventions;

    public AttentionNotifier(AttentionRepository items, NtfyClient ntfy, NotifyProperties props, Conventions conventions) {
        this.items = items;
        this.ntfy = ntfy;
        this.props = props;
        this.conventions = conventions;
    }

    /** Pushes what is due now; returns how many items went out. */
    public synchronized int flush(ZonedDateTime now) {
        if (!ntfy.configured()) return 0;
        QuietHours quiet = QuietHours.of(conventions.global(Conventions.QUIET_HOURS).orElse(null));
        List<AttentionItem> due = PushPlanner.plan(items.findUnpushed(), items.lastBatchedPushAt(), now, quiet,
                props.batchWindow());
        if (due.isEmpty()) return 0;
        boolean critical = due.stream().anyMatch(AttentionItem::critical);
        try {
            ntfy.publish(props.ntfy().attentionTopic(), title(due), message(due), critical ? 5 : 4);
        } catch (NtfyClient.NotifyException e) {
            log.warn("could not push {} attention item(s), will retry: {}", due.size(), e.getMessage());
            return 0;
        }
        items.markPushed(due.stream().map(AttentionItem::id).toList(), now.toOffsetDateTime());
        log.info("pushed {} attention item(s)", due.size());
        return due.size();
    }

    static String title(List<AttentionItem> due) {
        return due.size() == 1 ? due.getFirst().title() : "orange: " + due.size() + " things need you";
    }

    static String message(List<AttentionItem> due) {
        String text = due.size() == 1 ? due.getFirst().body()
                : String.join("\n", due.stream().map(i -> "• " + i.title()).toList());
        return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE - 1) + "…";
    }

    @Scheduled(fixedDelayString = "${orange.notify.sweep:30s}")
    void sweep() {
        if (props.enabled()) flushSafely();
    }

    @EventListener
    void onNotification(PgNotificationEvent event) {
        if (props.enabled() && "attention".equals(event.channel())) {
            Thread.ofVirtual().name("attention-notifier").start(this::flushSafely);
        }
    }

    private void flushSafely() {
        try {
            flush(ZonedDateTime.now(props.zoneId()));
        } catch (RuntimeException e) {
            log.warn("pushing attention items failed: {}", e.toString());
        }
    }
}
