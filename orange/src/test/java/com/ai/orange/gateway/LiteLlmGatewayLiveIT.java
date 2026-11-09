package com.ai.orange.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Key lifecycle against a real LiteLLM (start it with {@code docker compose --profile gateway up -d litellm}).
 * Makes no model calls, so it costs nothing. Run with {@code ./gradlew liveTest}.
 */
@Tag("live")
@EnabledIfEnvironmentVariable(named = "LITELLM_MASTER_KEY", matches = ".+")
class LiteLlmGatewayLiveIT {

    final GatewayClient client = new GatewayClient(new GatewayProperties(
            URI.create(System.getenv().getOrDefault("LITELLM_BASE_URL", "http://localhost:4000")),
            System.getenv("LITELLM_MASTER_KEY"), Duration.ofMinutes(30)));

    @Test
    void mintInspectBlockDelete() {
        assertThat(client.healthy()).as("LiteLLM is not answering on its liveness probe").isTrue();

        VirtualKey key = client.mint("live-it-" + UUID.randomUUID(), List.of("claude-haiku-5-5"),
                new BigDecimal("0.01"), Duration.ofMinutes(5), Map.of("source", "LiteLlmGatewayLiveIT"));
        try {
            assertThat(key.key()).startsWith("sk-");
            assertThat(key.expiresAt()).isNotNull();

            KeyInfo fresh = client.info(key.key());
            assertThat(fresh.spend()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(fresh.blocked()).isFalse();

            client.block(key.key());
            assertThat(client.info(key.key()).blocked()).isTrue();
        } finally {
            client.delete(key.key());
        }
    }

    /** The spend sync's two calls on v1.106.6: /key/list filtered by key_alias, /key/delete by key_aliases. */
    @Test
    void aliasLookupAndDeleteWork() {
        String alias = "live-it-" + UUID.randomUUID();
        String other = "live-it-other-" + UUID.randomUUID();
        VirtualKey key = client.mint(alias, List.of("claude-haiku-5-5"), new BigDecimal("0.01"), Duration.ofMinutes(5),
                Map.of("source", "LiteLlmGatewayLiveIT"));
        VirtualKey otherKey = client.mint(other, List.of("claude-haiku-5-5"), new BigDecimal("0.01"),
                Duration.ofMinutes(5), Map.of("source", "LiteLlmGatewayLiveIT"));
        try {
            assertThat(client.spendByAlias(alias)).hasValueSatisfying(s -> assertThat(s).isEqualByComparingTo("0"));
            client.block(key.key());
            assertThat(client.spendByAlias(alias)).as("a blocked key still reports its spend").isPresent();

            client.deleteByAlias(alias);

            assertThat(client.spendByAlias(alias)).isEmpty();
            assertThat(client.spendByAlias(other)).as("only the named alias was deleted").isPresent();
            client.deleteByAlias(alias); // already gone: no error
        } finally {
            client.delete(otherKey.key());
        }
    }
}
