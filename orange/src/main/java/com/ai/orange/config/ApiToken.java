package com.ai.orange.config;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The coordinator's API token ({@code orange.api.token}, {@code ORANGE_API_TOKEN}): the bearer
 * token the reporter, steering clients and the operator send to the whole operator API (tasks,
 * plans, dispatch, scheduler, runs, sessions, attention, {@code /mcp/steering}, the brief,
 * metrics). Probes, the webhook (HMAC) and {@code /mcp/worker} (per-run tokens) authenticate
 * otherwise. With no token configured everything is open, which is only right while orange
 * listens on localhost and nothing proxies to it; behind {@code tailscale serve} every request
 * comes from 127.0.0.1, so on the VPS the token is mandatory.
 *
 * <p>One brief's page ({@code /brief/{id}}) is the exception: a phone that taps a push sends no
 * header, so {@code BriefPageController} takes this token or the brief's own page key.
 *
 * <p>The check is an authorization rule inside the security chain ({@link SecurityConfig}), so it
 * applies to whatever path Spring Security matched, however the request spelled it.
 */
public final class ApiToken {

    private final byte[] token;

    public ApiToken(String token) {
        this.token = token == null || token.isBlank() ? null : token.strip().getBytes(StandardCharsets.UTF_8);
    }

    /** Whether this request may pass: no token is configured, or it carries the right one. */
    public boolean allows(HttpServletRequest request) {
        if (token == null) return true;
        String header = request.getHeader("Authorization");
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && MessageDigest.isEqual(header.substring(7).trim().getBytes(StandardCharsets.UTF_8), token);
    }
}
