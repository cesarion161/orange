package com.ai.orange.brief;

import com.ai.orange.notify.NotifyProperties;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The brief's clock: every sweep it writes the brief that is due, if any, pushes the one the phone
 * has not had, and deletes briefs past their retention. A brief run can take minutes, so a tick runs on a thread of its own and never
 * holds another scheduled job; while one runs, later sweeps do nothing. For the same reason each
 * step reads the time again: the push after a brief run is not made at the time from before it.
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
        sweep(() -> ZonedDateTime.now(notify.zoneId()));
    }

    /**
     * One sweep by {@code clock}, on a thread of its own. With the briefs switched off nothing is
     * written or pushed, and with pushes switched off ({@code orange.notify.enabled}) nothing is
     * pushed; old briefs are deleted either way, because {@code orange_focus} still writes some.
     *
     * @return the tick's thread; null while an earlier tick is still running
     */
    Thread sweep(Supplier<ZonedDateTime> clock) {
        if (!ticking.compareAndSet(false, true)) return null;
        try {
            return Thread.ofVirtual().name("brief-tick").start(() -> {
                try {
                    steps(clock, props.enabled(), props.enabled() && notify.enabled());
                } finally {
                    ticking.set(false);
                }
            });
        } catch (RuntimeException e) {
            ticking.set(false);
            log.warn("could not start the brief tick: {}", e.toString());
            return null;
        }
    }

    /** One look at the schedule as of {@code now}, whatever the switches say: what tests call. */
    public void tick(ZonedDateTime now) {
        steps(() -> now, true, true);
    }

    /** Each step stands alone: one failing does not stop the next. */
    private void steps(Supplier<ZonedDateTime> clock, boolean write, boolean push) {
        if (write) {
            try {
                service.scheduled(at(clock));
            } catch (RuntimeException e) {
                log.warn("writing the scheduled brief failed: {}", e.toString());
            }
        }
        if (push) {
            try {
                delivery.deliver(at(clock));
            } catch (RuntimeException e) {
                log.warn("pushing the brief failed: {}", e.toString());
            }
        }
        try {
            int deleted = briefs.deleteOlderThan(at(clock).toOffsetDateTime().minus(props.retention()));
            if (deleted > 0) log.info("brief retention: deleted {} brief(s) older than {}", deleted, props.retention());
        } catch (RuntimeException e) {
            log.warn("deleting old briefs failed: {}", e.toString());
        }
    }

    private ZonedDateTime at(Supplier<ZonedDateTime> clock) {
        return clock.get().withZoneSameInstant(notify.zoneId());
    }
}
