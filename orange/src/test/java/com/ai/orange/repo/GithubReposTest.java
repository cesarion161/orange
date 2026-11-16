package com.ai.orange.repo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Creating a repo through GitHub's REST API, played by a local HTTP server: the token stays in one header. */
class GithubReposTest {

    static final String TOKEN = "github_pat_s3cr3t-value";

    record Seen(String method, String uri, String authorization, String body) {}

    HttpServer server;
    final List<Seen> seen = new CopyOnWriteArrayList<>();
    volatile int createStatus = 201;
    volatile String createBody;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        try {
            server.stop(0);
        } catch (IllegalStateException alreadyStopped) {
            // the unreachable case stops it itself
        }
    }

    void handle(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().toString(),
                ex.getRequestHeaders().getFirst("Authorization"), body));
        String path = ex.getRequestURI().getPath();
        if (path.equals("/user")) {
            respond(ex, 200, "{\"login\":\"Me\"}");
        } else if (path.endsWith("/repos") && ex.getRequestMethod().equals("POST")) {
            String name = Json.mapper().readTree(body).path("name").asText();
            String owner = path.startsWith("/orgs/") ? path.split("/")[2] : "Me";
            respond(ex, createStatus, createBody != null ? createBody
                    : "{\"full_name\":\"" + owner + "/" + name + "\",\"private\":true}");
        } else {
            respond(ex, 404, "{\"message\":\"Not Found\"}");
        }
    }

    static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    GithubRepos github(String tokens) {
        return new GithubRepos(new RepoProperties(null, "https://github.com/", tokens,
                "http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @Test
    void sendsTheTokenOnlyInTheHeader() throws Exception {
        RepoKey mine = github("me=" + TOKEN + ",acme=" + TOKEN).create("me", "New-App");

        assertThat(mine).isEqualTo(RepoKey.of("me/new-app"));
        assertThat(seen).extracting(Seen::uri).containsExactly("/user", "/user/repos");
        assertThat(seen).allSatisfy(s -> {
            assertThat(s.authorization()).isEqualTo("Bearer " + TOKEN);
            assertThat(s.uri()).doesNotContain(TOKEN);
            assertThat(s.body()).doesNotContain(TOKEN);
        });
        JsonNode body = Json.mapper().readTree(seen.get(1).body());
        assertThat(body.path("name").asText()).isEqualTo("New-App");
        assertThat(body.path("private").asBoolean()).isTrue();
        assertThat(body.path("auto_init").asBoolean()).isTrue();

        seen.clear();
        RepoKey inOrg = github("acme=" + TOKEN).create("acme", "tool");
        assertThat(inOrg).isEqualTo(RepoKey.of("acme/tool"));
        assertThat(seen).extracting(Seen::method, Seen::uri)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("GET", "/user"),
                        org.assertj.core.groups.Tuple.tuple("POST", "/orgs/acme/repos"));
    }

    @Test
    void anErrorNeverCarriesTheToken() {
        createStatus = 422;
        createBody = "{\"message\":\"Repository creation failed for Bearer " + TOKEN + "\","
                + "\"errors\":[{\"message\":\"name already exists on this account\"}]}";

        assertThatThrownBy(() -> github("me=" + TOKEN).create("me", "taken"))
                .isInstanceOf(GithubRepos.GithubException.class)
                .hasMessageContaining("422")
                .hasMessageContaining("name already exists on this account")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN));

        assertThatThrownBy(() -> github("me=" + TOKEN).create("other", "x"))
                .isInstanceOf(GithubRepos.GithubException.class)
                .hasMessage("orange has no GitHub token for other");

        int port = server.getAddress().getPort();
        server.stop(0);
        GithubRepos unreachable = new GithubRepos(new RepoProperties(null, "https://github.com/", "me=" + TOKEN,
                "http://127.0.0.1:" + port));
        assertThatThrownBy(() -> unreachable.create("me", "x"))
                .isInstanceOf(GithubRepos.GithubException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(TOKEN).contains("GitHub"));
    }
}
