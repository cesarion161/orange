package com.ai.orange.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GatewayClientTest {

    static final URI BASE = URI.create("http://localhost:4000");

    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    GatewayClient client(String masterKey) {
        return new GatewayClient(new GatewayProperties(BASE, masterKey, Duration.ofMinutes(30)), builder);
    }

    @Test
    void mintSendsBudgetModelsAndDuration() {
        server.expect(requestTo("http://localhost:4000/key/generate"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer mk-123"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.key_alias").value("run-abc"))
                .andExpect(jsonPath("$.models[0]").value("claude-sonnet-5-5"))
                .andExpect(jsonPath("$.models[1]").value("claude-haiku-5-5"))
                .andExpect(jsonPath("$.max_budget").value(4))
                .andExpect(jsonPath("$.duration").value("95m"))
                .andExpect(jsonPath("$.metadata.run_id").value("abc"))
                .andRespond(withSuccess("{\"key\":\"sk-x\",\"key_alias\":\"run-abc\",\"expires\":\"2026-11-03T12:00:00Z\"}",
                        MediaType.APPLICATION_JSON));

        VirtualKey key = client("mk-123").mint("run-abc", List.of("claude-sonnet-5-5", "claude-haiku-5-5"),
                new BigDecimal("4"), Duration.ofMinutes(95), Map.of("run_id", "abc"));

        assertThat(key.alias()).isEqualTo("run-abc");
        assertThat(key.key()).isEqualTo("sk-x");
        assertThat(key.expiresAt()).isEqualTo(Instant.parse("2026-11-03T12:00:00Z"));
        assertThat(key.toString()).doesNotContain("sk-x").contains("run-abc");
        server.verify();
    }

    @Test
    void mintRoundsDurationUpToWholeMinutesAndAcceptsOffsetTimestamps() {
        server.expect(requestTo("http://localhost:4000/key/generate"))
                .andExpect(jsonPath("$.duration").value("2m"))
                .andRespond(withSuccess("{\"key\":\"sk-y\",\"expires\":\"2026-11-03T12:00:00.123456+00:00\"}",
                        MediaType.APPLICATION_JSON));
        VirtualKey key = client("mk").mint("run-x", List.of("m"), BigDecimal.ONE, Duration.ofSeconds(61), Map.of());
        assertThat(key.expiresAt()).isEqualTo(Instant.parse("2026-11-03T12:00:00.123456Z"));
    }

    @Test
    void infoParsesSpend() {
        server.expect(requestTo("http://localhost:4000/key/info?key=sk-x"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(queryParam("key", "sk-x"))
                .andExpect(header("Authorization", "Bearer mk"))
                .andRespond(withSuccess("{\"key\":\"sk-x\",\"info\":{\"spend\":1.25,\"blocked\":true,\"max_budget\":4}}",
                        MediaType.APPLICATION_JSON));
        KeyInfo info = client("mk").info("sk-x");
        assertThat(info.spend()).isEqualByComparingTo("1.25");
        assertThat(info.blocked()).isTrue();
    }

    @Test
    void infoTreatsNullBlockedAsFalse() {
        server.expect(requestTo("http://localhost:4000/key/info?key=sk-x"))
                .andRespond(withSuccess("{\"info\":{\"spend\":0.0,\"blocked\":null}}", MediaType.APPLICATION_JSON));
        KeyInfo info = client("mk").info("sk-x");
        assertThat(info.spend()).isEqualByComparingTo("0");
        assertThat(info.blocked()).isFalse();
    }

    @Test
    void blockAndDeleteHitEndpoints() {
        server.expect(requestTo("http://localhost:4000/key/block"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer mk"))
                .andExpect(jsonPath("$.key").value("sk-x"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:4000/key/delete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.keys[0]").value("sk-x"))
                .andRespond(withSuccess("{\"deleted_keys\":[\"sk-x\"]}", MediaType.APPLICATION_JSON));

        GatewayClient c = client("mk");
        c.block("sk-x");
        c.delete("sk-x");
        server.verify();
    }

    @Test
    void missingMasterKeyFailsBeforeHttp() {
        server.expect(ExpectedCount.never(), requestTo("http://localhost:4000/key/generate"));
        GatewayClient c = client("  ");
        assertThatThrownBy(() -> c.mint("run-x", List.of("m"), BigDecimal.ONE, Duration.ofMinutes(5), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("gateway master key not configured");
        assertThatThrownBy(() -> c.block("sk")).isInstanceOf(IllegalStateException.class);
        server.verify();
    }

    @Test
    void adminErrorsSurfaceWithoutTheKey() {
        server.expect(requestTo("http://localhost:4000/key/block")).andRespond(withServerError());
        assertThatThrownBy(() -> client("mk").block("sk-secret-value"))
                .isInstanceOf(GatewayException.class)
                .hasMessageNotContaining("sk-secret-value");
    }

    @Test
    void healthyTrueOn200() {
        server.expect(requestTo("http://localhost:4000/health/liveliness"))
                .andRespond(withSuccess("\"I'm alive!\"", MediaType.APPLICATION_JSON));
        assertThat(client("").healthy()).isTrue();
    }

    @Test
    void healthyFalseOnConnectionError() {
        server.expect(requestTo("http://localhost:4000/health/liveliness"))
                .andRespond(withException(new IOException("Connection refused")));
        assertThat(client("mk").healthy()).isFalse();
    }

    @Test
    void spendByAliasReadsKeyList() {
        server.expect(requestTo("http://localhost:4000/key/list?key_alias=run-abc&return_full_object=true"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "Bearer mk"))
                .andRespond(withSuccess("{\"keys\":[{\"key_alias\":\"run-abc\",\"spend\":0.0731,\"blocked\":true}],"
                        + "\"total_count\":1,\"current_page\":1,\"total_pages\":1}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:4000/key/list?key_alias=run-gone&return_full_object=true"))
                .andRespond(withSuccess("{\"keys\":[],\"total_count\":0}", MediaType.APPLICATION_JSON));

        assertThat(client("mk").spendByAlias("run-abc")).hasValueSatisfying(s -> assertThat(s).isEqualByComparingTo("0.0731"));
        assertThat(client("mk").spendByAlias("run-gone")).isEmpty();
        server.verify();
    }

    @Test
    void deleteByAliasSendsAliases() {
        server.expect(requestTo("http://localhost:4000/key/delete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer mk"))
                .andExpect(jsonPath("$.key_aliases[0]").value("run-abc"))
                .andRespond(withSuccess("{\"deleted_keys\":[\"x\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://localhost:4000/key/delete"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.NOT_FOUND)
                        .body("{\"error\":{\"message\":\"No keys found\",\"code\":\"404\"}}"));

        client("mk").deleteByAlias("run-abc");
        client("mk").deleteByAlias("run-already-gone"); // LiteLLM answers 404: nothing left to delete
        server.verify();
    }

    @Test
    void readTimeoutFailsFast() throws Exception {
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0)) {
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try {
                    while (!silent.isClosed()) {
                        java.net.Socket s = silent.accept(); // accept, then never answer
                        s.getInputStream();
                    }
                } catch (IOException ignored) {
                    // closed
                }
            });
            GatewayClient slow = new GatewayClient(new GatewayProperties(
                    URI.create("http://127.0.0.1:" + silent.getLocalPort()), "mk", Duration.ofMinutes(30),
                    Duration.ofSeconds(1), Duration.ofMillis(200)));
            long started = System.nanoTime();

            assertThatThrownBy(() -> slow.info("sk-x")).isInstanceOf(GatewayException.class);

            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            acceptor.interrupt();
        }
    }
}
