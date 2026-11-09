package com.ai.orange.gateway;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LiteLLM gateway settings ({@code orange.gateway}). Only runs whose routing tier has
 * {@code auth: gateway} use it.
 *
 * @param baseUrl        the LiteLLM proxy root; harnesses append their own API paths
 * @param masterKey      LiteLLM admin key; lives only in the coordinator, never in a run
 * @param keyTtlMargin   added to a run's timeout when choosing its virtual key's expiry
 * @param connectTimeout how long the admin client waits to connect
 * @param readTimeout    how long the admin client waits for an answer, so a hung proxy cannot hold a run
 */
@ConfigurationProperties(prefix = "orange.gateway")
public record GatewayProperties(
        @DefaultValue("http://localhost:4000") URI baseUrl,
        String masterKey,
        @DefaultValue("30m") Duration keyTtlMargin,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("30s") Duration readTimeout) {

    @ConstructorBinding
    public GatewayProperties {
        if (connectTimeout == null) connectTimeout = Duration.ofSeconds(5);
        if (readTimeout == null) readTimeout = Duration.ofSeconds(30);
    }

    /** Settings with the default timeouts. */
    public GatewayProperties(URI baseUrl, String masterKey, Duration keyTtlMargin) {
        this(baseUrl, masterKey, keyTtlMargin, null, null);
    }

    @Override
    public String toString() {
        return "GatewayProperties[baseUrl=" + baseUrl + ", masterKey=***, keyTtlMargin=" + keyTtlMargin
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + "]";
    }
}
