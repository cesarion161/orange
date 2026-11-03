package com.ai.orange.billing;

import com.ai.orange.billing.BillingProperties.Price;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Estimates a run's cost from token usage at list prices. Subscription runs are not billed per
 * token, so this is what lets orange cap them and compare them with gateway runs.
 */
@Component
public class CostEstimator {

    private static final Logger log = LoggerFactory.getLogger(CostEstimator.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private final Map<String, Price> prices;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    public CostEstimator(BillingProperties props) {
        this.prices = props.prices() == null ? Map.of() : props.prices();
    }

    /** USD for the given usage; zero (with one warning per model) when the model has no price. */
    public BigDecimal estimate(String model, long in, long out, long cacheRead, long cacheWrite) {
        Price p = model == null ? null : prices.get(model);
        if (p == null) {
            if (warned.add(String.valueOf(model))) {
                log.warn("no price configured for model '{}'; its runs count as $0 against budgets", model);
            }
            return BigDecimal.ZERO;
        }
        return perMillion(p.input(), in)
                .add(perMillion(p.output(), out))
                .add(perMillion(p.cacheRead(), cacheRead))
                .add(perMillion(p.cacheWrite(), cacheWrite));
    }

    private static BigDecimal perMillion(BigDecimal pricePerMillion, long tokens) {
        if (pricePerMillion == null || tokens == 0) return BigDecimal.ZERO;
        return pricePerMillion.multiply(BigDecimal.valueOf(tokens)).divide(MILLION, MathContext.DECIMAL64);
    }
}
