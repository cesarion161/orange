package com.ai.orange.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * On the VPS orange listens on loopback ({@code server.address}) and on the run network's gateway,
 * and nowhere else. Here ::1 stands in for the gateway: an address every test machine has.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Import(TestcontainersConfiguration.class)
class ExtraListenAddressesIT {

    static final int PORT = freePort();

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void listen(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("server.address", () -> "127.0.0.1");
        registry.add("orange.server.extra-addresses", () -> "::1");
    }

    int health(String host) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://" + host + ":" + PORT + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void orangeAnswersOnLoopbackAndOnEachExtraAddress() throws Exception {
        assertThat(health("127.0.0.1")).isEqualTo(200);
        assertThat(health("[::1]")).isEqualTo(200);
    }
}
