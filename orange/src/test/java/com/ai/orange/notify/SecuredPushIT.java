package com.ai.orange.notify;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.orange.TestcontainersConfiguration;
import com.ai.orange.attention.AttentionItem;
import com.ai.orange.attention.AttentionRepository;
import com.ai.orange.attention.AttentionService;
import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A coordinator with an API token and an ntfy server: the new endpoints want the token, and
 * urgent items reach the phone by the push discipline.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"orange.api.token=s3cret-token", "orange.notify.ntfy.attention-topic=my-topic",
                "orange.notify.ntfy.token=ntfy-token"})
@Import(TestcontainersConfiguration.class)
class SecuredPushIT {

    static final List<JsonNode> PUBLISHED = new CopyOnWriteArrayList<>();
    static final List<String> AUTH = new CopyOnWriteArrayList<>();
    static final AtomicInteger STATUS = new AtomicInteger(200);
    static final HttpServer NTFY = startNtfy();
    static final ZonedDateTime NOON = ZonedDateTime.now(ZoneId.systemDefault()).withHour(12).withMinute(0);

    static HttpServer startNtfy() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                if (STATUS.get() == 200) {
                    PUBLISHED.add(Json.mapper().readTree(body));
                    AUTH.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
                }
                exchange.sendResponseHeaders(STATUS.get(), -1);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void ntfy(DynamicPropertyRegistry registry) {
        registry.add("orange.notify.ntfy.url", () -> "http://127.0.0.1:" + NTFY.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        NTFY.stop(0);
    }

    @Value("${local.server.port}") int port;
    @Autowired AttentionService attention;
    @Autowired AttentionRepository items;
    @Autowired AttentionNotifier notifier;
    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clean() {
        // Earlier tests' items must not ride along in this test's pushes or hold its window.
        items.find(null, 200).forEach(i -> items.resolve(i.id(), "test", null));
        items.markPushed(items.find(null, 200).stream().map(AttentionItem::id).toList(), NOON.minusDays(1).toOffsetDateTime());
        PUBLISHED.clear();
        AUTH.clear();
        STATUS.set(200);
    }

    AttentionItem raise(String urgency, String title) {
        return attention.raise("test", "push:" + UUID.randomUUID(), urgency, null, null, title, "body of " + title);
    }

    int status(String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, "GET".equals(method) ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString("{}", StandardCharsets.UTF_8));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void theNewEndpointsWantTheToken() throws Exception {
        for (String path : List.of("/sessions", "/attention")) {
            assertThat(status("GET", path, null)).as(path).isEqualTo(401);
            assertThat(status("GET", path, "wrong")).as(path).isEqualTo(401);
            assertThat(status("GET", path, "s3cret-token")).as(path).isEqualTo(200);
        }
        assertThat(status("POST", "/sessions/events", null)).isEqualTo(401);
        assertThat(status("POST", "/sessions/events", "s3cret-token")).isEqualTo(400); // past the filter: a bad event
        assertThat(status("POST", "/attention/" + UUID.randomUUID() + "/resolve", null)).isEqualTo(401);
        assertThat(status("POST", "/mcp/steering", null)).isEqualTo(401);
        assertThat(status("POST", "/mcp/steering", "s3cret-token")).isNotEqualTo(401);
        // The older operator API is as open as before, and the worker endpoint keeps its own tokens.
        assertThat(status("GET", "/tasks", null)).isEqualTo(200);
        assertThat(status("POST", "/mcp/worker", "s3cret-token")).isEqualTo(401);
    }

    @Test
    void anUrgentItemIsPushedOnceAndNormalOnesNever() {
        raise("normal", "can wait");
        AttentionItem urgent = raise("urgent", "orange-ai · main asks: proceed?");

        assertThat(notifier.flush(NOON)).isEqualTo(1);
        assertThat(PUBLISHED).singleElement().satisfies(m -> {
            assertThat(m.path("topic").asText()).isEqualTo("my-topic");
            assertThat(m.path("title").asText()).isEqualTo("orange-ai · main asks: proceed?");
            assertThat(m.path("message").asText()).contains("body of");
            assertThat(m.path("priority").asInt()).isEqualTo(4);
        });
        assertThat(AUTH).containsExactly("Bearer ntfy-token");
        assertThat(items.findById(urgent.id()).orElseThrow().pushedAt()).isNotNull();
        assertThat(notifier.flush(NOON)).isZero();
        assertThat(PUBLISHED).hasSize(1);
    }

    @Test
    void urgentItemsInsideTheWindowGoOutTogetherLater() {
        raise("urgent", "first");
        assertThat(notifier.flush(NOON)).isEqualTo(1);
        raise("urgent", "second");
        raise("urgent", "third");
        assertThat(notifier.flush(NOON.plusMinutes(5))).isZero();

        assertThat(notifier.flush(NOON.plusMinutes(11))).isEqualTo(2);
        assertThat(PUBLISHED).hasSize(2);
        assertThat(PUBLISHED.get(1).path("title").asText()).isEqualTo("orange: 2 things need you");
        assertThat(PUBLISHED.get(1).path("message").asText()).contains("• second").contains("• third");
    }

    @Test
    void criticalGoesThroughQuietHoursAndTheWindowUrgentDoesNot() {
        raise("urgent", "first");
        notifier.flush(NOON);
        raise("urgent", "held by the window");
        AttentionItem critical = raise("critical", "prod is down");

        assertThat(notifier.flush(NOON.plusMinutes(1))).isEqualTo(1);
        assertThat(PUBLISHED.getLast().path("title").asText()).isEqualTo("prod is down");
        assertThat(PUBLISHED.getLast().path("priority").asInt()).isEqualTo(5);
        assertThat(items.findById(critical.id()).orElseThrow().pushedAt()).isNotNull();

        // 23:00 is inside the default quiet hours (22:00–08:00): the urgent item still waits.
        assertThat(notifier.flush(NOON.withHour(23))).isZero();
        assertThat(notifier.flush(NOON.plusDays(1))).isEqualTo(1);
    }

    @Test
    void aFailedPushIsTriedAgain() {
        AttentionItem urgent = raise("urgent", "retry me");
        STATUS.set(500);
        assertThat(notifier.flush(NOON)).isZero();
        assertThat(items.findById(urgent.id()).orElseThrow().pushedAt()).isNull();

        STATUS.set(200);
        assertThat(notifier.flush(NOON)).isEqualTo(1);
        assertThat(PUBLISHED).hasSize(1);
    }

    @Test
    void anAckedOrResolvedItemIsNotPushed() {
        AttentionItem acked = raise("urgent", "acked");
        AttentionItem resolved = raise("urgent", "resolved");
        attention.ack(acked.id());
        attention.resolve(resolved.id(), "done", "user");
        assertThat(notifier.flush(NOON)).isZero();
        assertThat(PUBLISHED).isEmpty();
    }
}
