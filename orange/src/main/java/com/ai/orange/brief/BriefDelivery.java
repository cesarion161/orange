package com.ai.orange.brief;

import com.ai.orange.notify.NotifyProperties;
import com.ai.orange.notify.NtfyClient;
import com.ai.orange.notify.QuietHours;
import com.ai.orange.project.Conventions;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Pushes a scheduled brief to the user's phone, on the brief's own ntfy topic. A brief counts as
 * delivered only once ntfy took it, so a failed push is tried again at the next tick, until the
 * brief time is older than {@code catch-up}: a late brief is noise. Briefs that were asked for
 * and briefs with nothing new are never pushed, and attention items are not touched here.
 */
@Component
public class BriefDelivery {

    private static final Logger log = LoggerFactory.getLogger(BriefDelivery.class);
    /** ntfy's default priority: nothing the model writes changes how the phone rings. */
    static final int PRIORITY = 3;

    private final BriefRepository briefs;
    private final NtfyClient ntfy;
    private final NotifyProperties notify;
    private final BriefProperties props;
    private final Conventions conventions;
    private final BriefLinks links;

    public BriefDelivery(BriefRepository briefs, NtfyClient ntfy, NotifyProperties notify, BriefProperties props,
                         Conventions conventions, BriefLinks links) {
        this.briefs = briefs;
        this.ntfy = ntfy;
        this.notify = notify;
        this.props = props;
        this.conventions = conventions;
        this.links = links;
    }

    /**
     * The brief to push now, if any: nothing in quiet hours; otherwise the one with the newest
     * brief time among those that should reach the phone, have not, and whose brief time is at
     * most {@code catchUp} ago.
     */
    static Optional<Brief> pick(List<Brief> undelivered, ZonedDateTime now, QuietHours quiet, Duration catchUp) {
        if (quiet.contains(now.toLocalTime())) return Optional.empty();
        return undelivered.stream()
                .filter(b -> b.scheduled() && b.pushable() && b.deliveredAt() == null)
                .filter(b -> !b.slotAt().toInstant().plus(catchUp).isBefore(now.toInstant()))
                .max(Comparator.comparing(b -> b.slotAt().toInstant()));
    }

    /** Pushes the brief that is due, if any; returns how many went out (0 or 1). */
    public synchronized int deliver(ZonedDateTime now) {
        if (!ntfy.configured()) return 0;
        ZonedDateTime at = now.withZoneSameInstant(notify.zoneId());
        QuietHours quiet = QuietHours.of(conventions.global(Conventions.QUIET_HOURS).orElse(null));
        Optional<Brief> due = pick(briefs.findUndelivered(), at, quiet, props.catchUp());
        if (due.isEmpty()) return 0;
        Brief b = due.get();
        try {
            ntfy.publish(notify.ntfy().briefTopic(), BriefText.title(b), BriefText.push(b), PRIORITY,
                    links.click(b).orElse(null));
        } catch (NtfyClient.NotifyException e) {
            log.warn("could not push brief {}, will retry: {}", b.id(), e.getMessage());
            return 0;
        }
        briefs.markDelivered(b.id(), at.toOffsetDateTime());
        log.info("pushed brief {} for {}", b.id(), b.slotAt());
        return 1;
    }
}
