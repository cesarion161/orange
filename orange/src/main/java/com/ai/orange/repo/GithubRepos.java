package com.ai.orange.repo;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Creates a GitHub repository for {@code orange_project_create} with {@code create: true}
 * (remote-first spec §7.1): private, with a first commit, under the token's own account or under an
 * organisation. Needs the owner's token to have the Administration permission.
 *
 * <p>The owner's token goes only into one request's {@code Authorization} header: never into a URL, a
 * request body, a log line or an exception message (anything GitHub answers is scrubbed of it too).
 */
@Component
public class GithubRepos {

    private static final Logger log = LoggerFactory.getLogger(GithubRepos.class);
    static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_MESSAGE = 300;

    /** GitHub refused, or could not be reached. The message is for the user, and never holds the token. */
    public static class GithubException extends RuntimeException {
        public GithubException(String message) {
            super(message);
        }
    }

    private final RepoProperties props;
    private final HttpClient http;

    public GithubRepos(RepoProperties props) {
        this.props = props;
        this.http = HttpClient.newBuilder().connectTimeout(TIMEOUT).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Creates {@code owner/name}: {@code POST /user/repos} when {@code owner} is the token's own login
     * ({@code GET /user}), else {@code POST /orgs/{owner}/repos}.
     *
     * @return the new repo's key, as GitHub names it
     * @throws GithubException with GitHub's status and message, or when orange has no token for {@code owner}
     * @throws IllegalArgumentException when {@code owner} or {@code name} is not a GitHub name
     */
    public RepoKey create(String owner, String name) {
        RepoKey wanted = new RepoKey(owner, name);
        String token = props.tokenFor(owner)
                .orElseThrow(() -> new GithubException("orange has no GitHub token for " + owner));
        JsonNode user = send(token, "GET", "/user", null, "reading the token's account");
        String login = user.path("login").asText("");
        String path = login.equalsIgnoreCase(owner) ? "/user/repos" : "/orgs/" + owner + "/repos";
        String body = Json.mapper().createObjectNode().put("name", name).put("private", true).put("auto_init", true)
                .toString();
        JsonNode repo = send(token, "POST", path, body, "creating " + owner + "/" + name);
        RepoKey created = RepoKey.parse("https://github.com/" + repo.path("full_name").asText(""), null)
                .orElse(wanted);
        log.info("created GitHub repo {}", created);
        return created;
    }

    private JsonNode send(String token, String method, String path, String body, String what) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(props.githubApiUrl() + path))
                .timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "orange")
                .header("Authorization", "Bearer " + token);
        request = body == null ? request.GET()
                : request.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GithubException(scrub("could not reach GitHub's API " + what + ": " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()), token));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GithubException("interrupted while " + what + " on GitHub");
        }
        JsonNode answer = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new GithubException(scrub("GitHub answered " + response.statusCode() + " " + what + ": "
                    + message(answer, response.body()), token));
        }
        return answer;
    }

    private static JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? Json.mapper().createObjectNode() : Json.mapper().readTree(body);
        } catch (IOException e) {
            return Json.mapper().createObjectNode();
        }
    }

    /** GitHub's {@code message} and the messages of its {@code errors}, on one line. */
    private static String message(JsonNode answer, String raw) {
        List<String> parts = new ArrayList<>();
        if (answer.hasNonNull("message")) parts.add(answer.path("message").asText());
        for (JsonNode e : answer.path("errors")) {
            String m = e.isTextual() ? e.asText() : e.path("message").asText("");
            if (!m.isBlank()) parts.add(m);
        }
        String text = parts.isEmpty() ? (raw == null ? "" : raw) : String.join("; ", parts);
        text = text.replaceAll("\\s+", " ").strip();
        return text.length() <= MAX_MESSAGE ? text : text.substring(0, MAX_MESSAGE) + "…";
    }

    private static String scrub(String text, String token) {
        return token == null || token.isEmpty() ? text : text.replace(token, "[redacted]");
    }
}
