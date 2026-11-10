package com.ai.orange.brief;

import com.ai.orange.notify.NotifyProperties;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The brief's clock: every sweep it writes the brief that is due, if any, pushes the one the phone
 * has not had, and deletes briefs past their retention. A brief run can take minutes, so a tick runs on a thread of its own and never
 * holds another scheduled job; while one runs, later sweeps do nothing.
 */
@Component
public class BriefScheduler {

    private static final Logger log = LoggerFactory.getLogger(BriefScheduler.class);

    private final BriefService service;
    private final BriefDelivery delivery;
    private final BriefRepository briefs;
    private final BriefProperties props;
    private final NotifyProperties notify;
    private final AtomicBoolean ticking = new AtomicBoolean();

    public BriefScheduler(BriefService service, BriefDelivery delivery, BriefRepository briefs,
                          BriefProperties props, NotifyProperties notify) {
        this.service = service;
        this.delivery = delivery;
        this.briefs = briefs;
        this.props = props;
        this.notify = notify;
    }

    @Scheduled(fixedDelayString = "${orange.brief.sweep:60s}")
    void sweep() {
        if (!props.enabled()) return;
        if (!ticking.compareAndSet(false, true)) return;
        try {
            Thread.ofVirtual().name("brief-tick").start(() -> {
                try {
                    tick(ZonedDateTime.now(notify.zoneId()));
                } finally {
                    ticking.set(false);
                }
            });
        } catch (RuntimeException e) {
            ticking.set(false);
            log.warn("could not start the brief tick: {}", e.toString());
        }
    }

    /** One look at the schedule as of {@code now}. Each step stands alone: one failing does not stop the next. */
    public void tick(ZonedDateTime now) {
        ZonedDateTime at = now.withZoneSameInstant(notify.zoneId());
        try {
            service.scheduled(at);
        } catch (RuntimeException e) {
            log.warn("writing the scheduled brief failed: {}", e.toString());
        }
        try {
            delivery.deliver(at);
        } catch (RuntimeException e) {
            log.warn("pushing the brief failed: {}", e.toString());
        }
        try {
            int deleted = briefs.deleteOlderThan(at.toOffsetDateTime().minus(props.retention()));
            if (deleted > 0) log.info("brief retention: deleted {} brief(s) older than {}", deleted, props.retention());
        } catch (RuntimeException e) {
            log.warn("deleting old briefs failed: {}", e.toString());
        }
    }
}
