package com.ai.orange.attention;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Rule-based attention ({@code orange.attention}), until the judge of Phase B decides instead.
 *
 * @param sweepEnabled         whether the sweeper runs (tests turn it off and sweep by hand)
 * @param sweep                how often waiting sessions are looked at
 * @param waitingEscalateAfter a session asking or waiting this long without the user makes its item urgent
 * @param idleAfter            a session that stopped with nothing to ask is filed after this long
 * @param idleStopUrgency      urgency of that item: {@code normal} (the brief) or {@code urgent} (a push)
 */
@ConfigurationProperties(prefix = "orange.attention")
public record AttentionProperties(@DefaultValue("true") boolean sweepEnabled,
                                  @DefaultValue("30s") Duration sweep,
                                  @DefaultValue("15m") Duration waitingEscalateAfter,
                                  @DefaultValue("15m") Duration idleAfter,
                                  @DefaultValue("normal") String idleStopUrgency) {

    public AttentionProperties {
        if (!AttentionItem.URGENCIES.contains(idleStopUrgency)) {
            throw new IllegalArgumentException("orange.attention.idle-stop-urgency must be one of "
                    + AttentionItem.URGENCIES + ": " + idleStopUrgency);
        }
    }
}
