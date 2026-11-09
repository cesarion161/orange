package com.ai.orange.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * LiteLLM admin client: mints a short-lived virtual key per gateway run, reads its spend, and
 * blocks and deletes it afterwards. Bodies are handled as JSON strings so the client does not
 * depend on which Jackson generation Spring's message converters pick.
 */
@Component
public class GatewayClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final GatewayProperties props;
    private final RestClient http;

    @Autowired
    public GatewayClient(GatewayProperties props) {
        this(props, RestClient.builder().requestFactory(requestFactory(props)));
    }

    /** Connect and read timeouts, so a LiteLLM that hangs fails the call instead of holding a run. */
    static SimpleClientHttpRequestFactory requestFactory(GatewayProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.connectTimeout());
        factory.setReadTimeout(props.readTimeout());
        return factory;
    }

    public GatewayClient(GatewayProperties props, RestClient.Builder builder) {
        this.props = props;
        this.http = builder.baseUrl(props.baseUrl().toString()).build();
    }

    /**
     * Mints a virtual key limited to {@code models}, capped at {@code maxBudgetUsd}, expiring after
     * {@code ttl} (rounded up to whole minutes).
     */
    public VirtualKey mint(String alias, List<String> models, BigDecimal maxBudgetUsd, Duration ttl,
                           Map<String, Object> metadata) {
        String master = requireMasterKey();
        ObjectNode body = JSON.createObjectNode();
        body.put("key_alias", alias);
        body.set("models", JSON.valueToTree(models));
        body.put("max_budget", maxBudgetUsd);
        body.put("duration", wholeMinutes(ttl) + "m");
        body.set("metadata", JSON.valueToTree(metadata));

        JsonNode response = call("mint key " + alias, () -> http.post().uri("/key/generate")
                .header("Authorization", "Bearer " + master)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class));
        String key = response.path("key").asText(null);
        if (key == null || key.isBlank()) {
            throw new GatewayException("LiteLLM returned no key for " + alias, null);
        }
        return new VirtualKey(alias, key, parseInstant(response.path("expires").asText(null)));
    }

    /** Blocks the key so it can no longer be used; its spend history stays readable. */
    public void block(String key) {
        String master = requireMasterKey();
        call("block key", () -> http.post().uri("/key/block")
                .header("Authorization", "Bearer " + master)
                .contentType(MediaType.APPLICATION_JSON)
                .body(JSON.createObjectNode().put("key", key).toString())
                .retrieve()
                .body(String.class));
    }

    public KeyInfo info(String key) {
        String master = requireMasterKey();
        JsonNode response = call("read key info", () -> http.get()
                .uri(b -> b.path("/key/info").queryParam("key", key).build())
                .header("Authorization", "Bearer " + master)
                .retrieve()
                .body(String.class));
        JsonNode info = response.path("info");
        BigDecimal spend = info.path("spend").isNumber() ? info.path("spend").decimalValue() : BigDecimal.ZERO;
        return new KeyInfo(spend, info.path("blocked").asBoolean(false));
    }

    public void delete(String key) {
        String master = requireMasterKey();
        ObjectNode body = JSON.createObjectNode();
        body.putArray("keys").add(key);
        call("delete key", () -> http.post().uri("/key/delete")
                .header("Authorization", "Bearer " + master)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body.toString())
                .retrieve()
                .body(String.class));
    }

    /**
     * The spend LiteLLM has booked on the key named {@code alias} (blocked keys included); empty
     * when no key has that alias any more.
     */
    public Optional<BigDecimal> spendByAlias(String alias) {
        String master = requireMasterKey();
        JsonNode response = call("list keys by alias", () -> http.get()
                .uri(b -> b.path("/key/list").queryParam("key_alias", alias)
                        .queryParam("return_full_object", "true").build())
                .header("Authorization", "Bearer " + master)
                .retrieve()
                .body(String.class));
        JsonNode keys = response.path("keys");
        if (!keys.isArray() || keys.isEmpty()) return Optional.empty();
        JsonNode spend = keys.get(0).path("spend");
        return Optional.of(spend.isNumber() ? spend.decimalValue() : BigDecimal.ZERO);
    }

    /** Deletes the key named {@code alias}. A key that is already gone is not an error. */
    public void deleteByAlias(String alias) {
        String master = requireMasterKey();
        ObjectNode body = JSON.createObjectNode();
        body.putArray("key_aliases").add(alias);
        try {
            http.post().uri("/key/delete")
                    .header("Authorization", "Bearer " + master)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toString())
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            // LiteLLM answers 404 "No keys found" when nothing has that alias.
        } catch (RestClientException e) {
            throw new GatewayException("LiteLLM delete key by alias failed: " + e.getClass().getSimpleName(), null);
        }
    }

    /** True when the proxy answers its liveness probe. Never throws. */
    public boolean healthy() {
        try {
            http.get().uri("/health/liveliness").retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private String requireMasterKey() {
        if (props.masterKey() == null || props.masterKey().isBlank()) {
            throw new IllegalStateException("gateway master key not configured");
        }
        return props.masterKey();
    }

    private static JsonNode call(String what, Supplier<String> request) {
        String raw;
        try {
            raw = request.get();
        } catch (RestClientException e) {
            // Exception messages can echo the request; report only what we were doing and the cause type.
            throw new GatewayException("LiteLLM " + what + " failed: " + e.getClass().getSimpleName(), null);
        }
        try {
            return raw == null || raw.isBlank() ? JSON.createObjectNode() : JSON.readTree(raw);
        } catch (Exception e) {
            throw new GatewayException("LiteLLM " + what + " returned unparseable JSON", null);
        }
    }

    private static long wholeMinutes(Duration ttl) {
        long seconds = Math.max(ttl.toSeconds(), 1);
        return (seconds + 59) / 60;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank() || "null".equals(value)) return null;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
