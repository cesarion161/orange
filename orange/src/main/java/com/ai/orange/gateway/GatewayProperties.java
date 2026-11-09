package com.ai.orange.gateway;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LiteLLM gateway settings ({@code orange.gateway}). Only runs whose routing tier has
 * {@code auth: gateway} use it.
 *
 * @param baseUrl      the LiteLLM proxy root; harnesses append their own API paths
 * @param masterKey    LiteLLM admin key; lives only in the coordinator, never in a run
 * @param keyTtlMargin added to a run's timeout when choosing its virtual key's expiry
 */
@ConfigurationProperties(prefix = "orange.gateway")
public record GatewayProperties(
        @DefaultValue("http://localhost:4000") URI baseUrl,
        String masterKey,
        @DefaultValue("30m") Duration keyTtlMargin) {

    @Override
    public String toString() {
        return "GatewayProperties[baseUrl=" + baseUrl + ", masterKey=***, keyTtlMargin=" + keyTtlMargin + "]";
    }
}
