package com.ai.orange.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.billing.BillingProperties.Price;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CostEstimatorTest {

    final CostEstimator estimator = new CostEstimator(new BillingProperties("tok", false, Map.of(
            "claude-sonnet-5-5", new Price(new BigDecimal("2.00"), new BigDecimal("10.00"),
                    new BigDecimal("0.20"), new BigDecimal("2.50")),
            "claude-opus-5-5", new Price(new BigDecimal("4.00"), new BigDecimal("20.00"),
                    new BigDecimal("0.20"), new BigDecimal("5.00")))));

    @Test
    void estimateUsesPerMillionPrices() {
        // 1M input * $2 + 100k output * $10/M + 2M cache reads * $0.20/M = 2.00 + 1.00 + 0.40
        assertThat(estimator.estimate("claude-sonnet-5-5", 1_000_000, 100_000, 2_000_000, 0))
                .isEqualByComparingTo("3.40");
    }

    @Test
    void cacheWritesArePriced() {
        // 400k cache writes on Opus at $5/M = 2.00
        assertThat(estimator.estimate("claude-opus-5-5", 0, 0, 0, 400_000)).isEqualByComparingTo("2.00");
    }

    @Test
    void smallUsageKeepsPrecision() {
        // 1,200 in + 80 out on Sonnet = 0.0024 + 0.0008
        assertThat(estimator.estimate("claude-sonnet-5-5", 1_200, 80, 0, 0)).isEqualByComparingTo("0.0032");
    }

    @Test
    void unknownModelIsZero() {
        assertThat(estimator.estimate("some-new-model", 5_000_000, 5_000_000, 0, 0)).isEqualByComparingTo("0");
    }

    @Test
    void shippedPriceTableBinds() throws Exception {
        var env = new org.springframework.core.env.StandardEnvironment();
        new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("application", new org.springframework.core.io.ClassPathResource("application.yaml"))
                .forEach(env.getPropertySources()::addLast);
        BillingProperties props = new org.springframework.boot.context.properties.bind.Binder(
                org.springframework.boot.context.properties.source.ConfigurationPropertySources.get(env))
                .bindOrCreate("orange.billing", BillingProperties.class);

        assertThat(props.overflowToGateway()).isFalse();
        assertThat(props.prices()).containsOnlyKeys(
                "claude-fable-5-1", "claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5");
        CostEstimator shipped = new CostEstimator(props);
        // 1M Haiku input at $0.10 + 1M Haiku output at $0.50
        assertThat(shipped.estimate("claude-haiku-5-5", 1_000_000, 1_000_000, 0, 0)).isEqualByComparingTo("0.60");
        // 1M Opus cache writes at $5.00
        assertThat(shipped.estimate("claude-opus-5-5", 0, 0, 0, 1_000_000)).isEqualByComparingTo("5.00");
    }

    @Test
    void propertiesToStringRedactsToken() {
        assertThat(new BillingProperties("plan-token-secret", false, Map.of()).toString())
                .doesNotContain("plan-token-secret");
    }
}
