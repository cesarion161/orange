package com.ai.orange.notify;

import com.ai.orange.attention.AttentionItem;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Push discipline (project-space spec decision 5), as a pure rule: critical items go out at once,
 * always. Urgent items go out together, at most one push per batch window, and never in quiet
 * hours. Everything else waits for the brief and never reaches this class.
 */
public final class PushPlanner {

    private PushPlanner() {}

    /**
     * @param unpushed        open urgent and critical items not pushed yet
     * @param lastBatchedPush when urgent items were last pushed
     * @return the items to push now, in the order given
     */
    public static List<AttentionItem> plan(List<AttentionItem> unpushed, Optional<OffsetDateTime> lastBatchedPush,
                                           ZonedDateTime now, QuietHours quiet, Duration batchWindow) {
        boolean holdUrgent = quiet.contains(now.toLocalTime())
                || lastBatchedPush.map(last -> Duration.between(last.toInstant(), now.toInstant())
                        .compareTo(batchWindow) < 0).orElse(false);
        return unpushed.stream().filter(i -> i.critical() || !holdUrgent).toList();
    }
}
