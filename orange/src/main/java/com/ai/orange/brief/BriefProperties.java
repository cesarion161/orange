package com.ai.orange.brief;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The focus brief ({@code orange.brief}; project-space spec §7, Phase D). The brief times
 * themselves are the global {@code brief-times} convention.
 *
 * @param enabled          whether briefs are written at the brief times; off, only when asked for
 * @param sweep            how often the schedule is looked at
 * @param catchUp          a missed brief time is still briefed, and a brief still pushed, this long after it
 * @param firstWindow      how far back the first brief looks
 * @param freshMinInterval {@code orange_focus} writes a fresh brief at most this often
 * @param focusWait        how long {@code orange_focus} waits for a fresh brief
 * @param publicUrl        where a phone reaches orange, without a trailing slash; blank = the push carries no link
 * @param linkTtl          how long a brief's page key opens its page
 * @param retention        briefs older than this are deleted
 */
@ConfigurationProperties(prefix = "orange.brief")
public record BriefProperties(@DefaultValue("true") boolean enabled,
                              @DefaultValue("60s") Duration sweep,
                              @DefaultValue("2h") Duration catchUp,
                              @DefaultValue("24h") Duration firstWindow,
                              @DefaultValue("10m") Duration freshMinInterval,
                              @DefaultValue("60s") Duration focusWait,
                              String publicUrl,
                              @DefaultValue("48h") Duration linkTtl,
                              @DefaultValue("30d") Duration retention) {

    public BriefProperties {
        publicUrl = publicUrl == null ? "" : publicUrl.strip();
        if (publicUrl.endsWith("/")) publicUrl = publicUrl.substring(0, publicUrl.length() - 1);
    }
}
