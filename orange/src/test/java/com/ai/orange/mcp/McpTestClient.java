package com.ai.orange.mcp;

import com.ai.orange.json.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only MCP client: JSON-RPC over HTTP POST, the way Claude Code talks to an HTTP MCP server
 * (Accept both JSON and SSE, bearer token from the config's headers).
 */
final class McpTestClient {

    static final String PROTOCOL_VERSION = "2025-06-18";

    private final HttpClient http = HttpClient.newHttpClient();
    private final URI uri;
    private final String token;
    private final AtomicInteger ids = new AtomicInteger();

    McpTestClient(String url, String tokenOrNull) {
        this.uri = URI.create(url);
        this.token = tokenOrNull;
    }

    /** Raw POST, for status-code assertions. */
    HttpResponse<String> post(String body) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Sends a JSON-RPC request and returns the response object ({@code result} or {@code error}). */
    JsonNode request(String method, JsonNode params) throws IOException, InterruptedException {
        ObjectNode req = Json.mapper().createObjectNode()
                .put("jsonrpc", "2.0").put("id", ids.incrementAndGet()).put("method", method);
        req.set("params", params == null ? Json.mapper().createObjectNode() : params);
        HttpResponse<String> res = post(req.toString());
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode() + ": " + res.body());
        }
        return parseBody(res.body());
    }

    void initialize() throws IOException, InterruptedException {
        ObjectNode params = Json.mapper().createObjectNode().put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "orange-test").put("version", "1");
        JsonNode res = request("initialize", params);
        if (res.has("error")) throw new IllegalStateException("initialize failed: " + res);
        ObjectNode note = Json.mapper().createObjectNode().put("jsonrpc", "2.0").put("method", "notifications/initialized");
        post(note.toString());
    }

    List<String> listTools() throws IOException, InterruptedException {
        JsonNode res = request("tools/list", null);
        List<String> names = new ArrayList<>();
        res.path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        return names;
    }

    /** The {@code result} of a tools/call, or the {@code error} object when the server rejected the call. */
    JsonNode callTool(String name, ObjectNode arguments) throws IOException, InterruptedException {
        ObjectNode params = Json.mapper().createObjectNode().put("name", name);
        params.set("arguments", arguments);
        JsonNode res = request("tools/call", params);
        return res.has("error") ? res.get("error") : res.path("result");
    }

    /** True when the call was refused, either as a JSON-RPC error or as a tool result with isError. */
    static boolean failed(JsonNode callResult) {
        return callResult.has("code") || callResult.path("isError").asBoolean(false);
    }

    static String text(JsonNode callResult) {
        StringBuilder sb = new StringBuilder();
        callResult.path("content").forEach(c -> sb.append(c.path("text").asText()));
        return sb.toString();
    }

    private static JsonNode parseBody(String body) throws IOException {
        String trimmed = body.strip();
        if (trimmed.startsWith("{")) return Json.mapper().readTree(trimmed);
        // SSE: the response is the last "data:" line.
        String last = null;
        for (String line : trimmed.split("\n")) {
            if (line.startsWith("data:")) last = line.substring(5).strip();
        }
        if (last == null) throw new IOException("no JSON-RPC response in: " + body);
        return Json.mapper().readTree(last);
    }
}
