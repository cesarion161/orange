package com.ai.orange.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Behind {@code tailscale serve} every request arrives from 127.0.0.1, so with a token set the
 * token is what guards the operator API: all of it, not only the session endpoints.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "orange.api.token=t0ken")
@Import(TestcontainersConfiguration.class)
class ApiTokenIT {

    @Value("${local.server.port}") int port;
    final HttpClient http = HttpClient.newHttpClient();

    int status(String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{}"))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void everyOperatorEndpointWantsTheToken() throws Exception {
        List<String[]> calls = List.of(
                new String[] {"GET", "/tasks"}, new String[] {"POST", "/tasks"},
                new String[] {"GET", "/plans"}, new String[] {"POST", "/dispatch"},
                new String[] {"GET", "/scheduler"}, new String[] {"POST", "/scheduler/pause"},
                new String[] {"GET", "/runs/" + UUID.randomUUID()},
                new String[] {"GET", "/events/live"}, new String[] {"GET", "/dev-envs"},
                new String[] {"GET", "/health/aggregate"},
                new String[] {"GET", "/actuator/prometheus"}, new String[] {"GET", "/actuator/metrics"});
        for (String[] c : calls) {
            assertThat(status(c[0], c[1], null)).as("%s %s without the token", c[0], c[1]).isEqualTo(403);
            assertThat(status(c[0], c[1], "wrong")).as("%s %s with a wrong token", c[0], c[1]).isEqualTo(403);
        }
        assertThat(status("GET", "/tasks", "t0ken")).isEqualTo(200);
        assertThat(status("GET", "/scheduler", "t0ken")).isEqualTo(200);
    }

    @Test
    void steeringContextNeedsTheToken() throws Exception {
        assertThat(status("GET", "/steering/context", null)).isEqualTo(403);
        assertThat(status("GET", "/steering/context", "wrong")).isEqualTo(403);
        HttpResponse<String> ok = http.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/steering/context"))
                .header("Authorization", "Bearer t0ken").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.headers().firstValue("Content-Type").orElse("")).startsWith("text/plain");
        assertThat(ok.body()).contains("build X in <project>");
        assertThat(ok.body().lines().count()).isLessThanOrEqualTo(8);
    }

    @Test
    void probesAndEndpointsWithTheirOwnAuthStayOpen() throws Exception {
        assertThat(status("GET", "/actuator/health", null)).isEqualTo(200);
        assertThat(status("GET", "/api/ready", null)).isEqualTo(200);
        assertThat(status("GET", "/api/status", null)).isEqualTo(200);
        // The worker endpoint checks its per-run token itself.
        assertThat(status("POST", "/mcp/worker", null)).isEqualTo(401);
    }
}
