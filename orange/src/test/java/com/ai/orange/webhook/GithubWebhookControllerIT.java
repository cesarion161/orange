package com.ai.orange.webhook;

import static com.ai.orange.db.jooq.Tables.EVENTS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.db.jooq.tables.records.EventsRecord;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

/** GitHub deliveries are verified, stored once each, and acknowledged; processing happens later. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class GithubWebhookControllerIT {

    static final String SECRET = "test-webhook-secret";
    static final String BODY = """
            {"action":"closed","repository":{"full_name":"acme/widgets"},"pull_request":{"number":7,"merged":true}}""";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("orange.github.webhook-secret", () -> SECRET);
    }

    @LocalServerPort int port;
    @Autowired DSLContext dsl;
    final RestTemplate http = new RestTemplate();

    @BeforeEach
    void clean() {
        dsl.deleteFrom(EVENTS).execute();
    }

    ResponseEntity<Map> deliver(String delivery, String body, String signature) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-GitHub-Event", "pull_request");
        if (delivery != null) headers.add("X-GitHub-Delivery", delivery);
        headers.add("X-Hub-Signature-256", signature);
        return http.exchange("http://localhost:" + port + "/webhooks/github", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    static String sign(String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void signedDeliveryIsStored() throws Exception {
        String delivery = UUID.randomUUID().toString();

        ResponseEntity<Map> response = deliver(delivery, BODY, sign(BODY));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("stored", true);
        EventsRecord row = dsl.selectFrom(EVENTS).fetchSingle();
        assertThat(row.getSource()).isEqualTo("github");
        assertThat(row.getType()).isEqualTo("pull_request");
        assertThat(row.getRepo()).isEqualTo("acme/widgets");
        assertThat(row.getDeliveryId()).isEqualTo(delivery);
        assertThat(row.getPayload().data()).contains("\"merged\": true");
        assertThat(row.getProcessedAt()).isNull();
    }

    @Test
    void duplicateDeliveryIsStoredOnce() throws Exception {
        String delivery = UUID.randomUUID().toString();

        deliver(delivery, BODY, sign(BODY));
        ResponseEntity<Map> again = deliver(delivery, BODY, sign(BODY));

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(again.getBody()).containsEntry("stored", false);
        assertThat(dsl.fetchCount(EVENTS)).isEqualTo(1);
    }

    @Test
    void aDeliveryWithoutAnIdIsKeyedByItsBody() throws Exception {
        deliver(null, BODY, sign(BODY));
        ResponseEntity<Map> again = deliver(null, BODY, sign(BODY));

        assertThat(again.getBody()).containsEntry("stored", false);
        assertThat(dsl.selectFrom(EVENTS).fetchSingle().getDeliveryId()).startsWith("body-");
    }

    @Test
    void badSignatureIsRejectedAndNothingStored() {
        assertThatThrownBy(() -> deliver(UUID.randomUUID().toString(), BODY, "sha256=" + "00".repeat(32)))
                .isInstanceOfSatisfying(HttpClientErrorException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        assertThat(dsl.fetchCount(EVENTS)).isZero();
    }
}
