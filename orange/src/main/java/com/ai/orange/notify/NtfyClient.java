package com.ai.orange.notify;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;

/** Publishes to an ntfy server through its JSON API (POST to the server root). */
@Component
public class NtfyClient {

    private final NotifyProperties.Ntfy props;
    private final HttpClient http;

    public NtfyClient(NotifyProperties props) {
        this.props = props.ntfy();
        this.http = HttpClient.newBuilder().connectTimeout(this.props.connectTimeout()).build();
    }

    public boolean configured() {
        return props.configured();
    }

    /**
     * @param priority ntfy's 1 (min) to 5 (max)
     * @throws NotifyException when the server cannot be reached or refuses the message
     */
    public void publish(String topic, String title, String message, int priority) {
        ObjectNode body = Json.mapper().createObjectNode()
                .put("topic", topic).put("title", title).put("message", message).put("priority", priority);
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(props.url().strip()))
                .timeout(props.readTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (props.token() != null && !props.token().isBlank()) {
            req.header("Authorization", "Bearer " + props.token().strip());
        }
        try {
            HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) throw new NotifyException("ntfy answered HTTP " + res.statusCode());
        } catch (IOException e) {
            throw new NotifyException("ntfy is unreachable: " + e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotifyException("interrupted while publishing to ntfy");
        }
    }

    public static class NotifyException extends RuntimeException {
        public NotifyException(String message) {
            super(message);
        }
    }
}
