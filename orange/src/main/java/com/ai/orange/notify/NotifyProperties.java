package com.ai.orange.notify;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Pushes to the user's phone ({@code orange.notify}).
 *
 * @param enabled     whether the notifier runs (tests turn it off and flush by hand)
 * @param sweep       how often unpushed items are looked at, besides the {@code attention} notification
 * @param batchWindow at most one push of urgent items per this long
 * @param zone        the user's time zone, for quiet hours; blank = the coordinator's
 * @param ntfy        the ntfy server; no pushes while its url is blank
 */
@ConfigurationProperties(prefix = "orange.notify")
public record NotifyProperties(@DefaultValue("true") boolean enabled,
                               @DefaultValue("30s") Duration sweep,
                               @DefaultValue("10m") Duration batchWindow,
                               String zone,
                               @DefaultValue Ntfy ntfy) {

    public NotifyProperties {
        // A bad zone must stop the start, not silently switch pushes off at every flush.
        if (zone != null && !zone.isBlank()) ZoneId.of(zone.strip());
    }

    public ZoneId zoneId() {
        return zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone.strip());
    }

    /**
     * @param url            base URL of the ntfy server, e.g. {@code https://ntfy.sh} or the VPS's own
     * @param attentionTopic topic for urgent and critical attention items; on a public server its name is the secret
     * @param briefTopic     topic for the focus brief, so the phone can treat it differently from what needs the user now
     * @param token          ntfy access token, when the server wants one
     */
    public record Ntfy(String url, @DefaultValue("attention") String attentionTopic,
                       @DefaultValue("brief") String briefTopic, String token,
                       @DefaultValue("5s") Duration connectTimeout, @DefaultValue("15s") Duration readTimeout) {

        public Ntfy {
            // An empty variable in .env binds as an empty string, which must not mean an empty topic.
            attentionTopic = attentionTopic == null || attentionTopic.isBlank() ? "attention" : attentionTopic.strip();
            briefTopic = briefTopic == null || briefTopic.isBlank() ? "brief" : briefTopic.strip();
        }

        public boolean configured() {
            return url != null && !url.isBlank();
        }
    }
}
