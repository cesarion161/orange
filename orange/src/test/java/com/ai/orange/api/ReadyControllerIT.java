package com.ai.orange.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * Verifies {@code GET /api/ready} returns the expected JSON body, is reachable
 * without auth (i.e. the SecurityConfig allowlist entry is wired), and pins
 * the field order so smoke-check string comparisons stay stable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class ReadyControllerIT {

    @LocalServerPort int port;

    final RestTemplate http = new RestTemplate();
    final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {};

    @Test
    void api_ready_returns_200_with_expected_body() {
        ResponseEntity<Map<String, Object>> response = http.exchange(
                "http://localhost:" + port + "/api/ready",
                org.springframework.http.HttpMethod.GET,
                null,
                MAP);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsEntry("ready", true)
                                       .containsEntry("service", "orange");
    }

    @Test
    void api_ready_field_order_is_stable() {
        // Hit the raw String body so we can assert key order, not just the
        // deserialized map. The LinkedHashMap in ReadyController is what
        // pins this.
        ResponseEntity<String> raw = http.exchange(
                "http://localhost:" + port + "/api/ready",
                org.springframework.http.HttpMethod.GET,
                null,
                String.class);
        assertThat(raw.getStatusCode().value()).isEqualTo(200);
        assertThat(raw.getBody())
                .as("'ready' must serialize before 'service'")
                .containsSubsequence("\"ready\"", "\"service\"");
    }
}
