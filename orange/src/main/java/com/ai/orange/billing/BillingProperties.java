package com.ai.orange.billing;

import java.math.BigDecimal;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Billing settings ({@code orange.billing}).
 *
 * @param subscriptionToken Max-plan token from {@code claude setup-token}; given only to the {@code claude} binary
 * @param overflowToGateway whether a subscription run may spill onto the API-billed gateway (user decision: no)
 * @param prices            per-model list prices, USD per million tokens, used to estimate subscription-run cost
 */
@ConfigurationProperties(prefix = "orange.billing")
public record BillingProperties(String subscriptionToken, boolean overflowToGateway, Map<String, Price> prices) {

    public record Price(BigDecimal input, BigDecimal output, BigDecimal cacheRead, BigDecimal cacheWrite) {}

    @Override
    public String toString() {
        return "BillingProperties[subscriptionToken=***, overflowToGateway=" + overflowToGateway
                + ", prices=" + prices + "]";
    }
}
