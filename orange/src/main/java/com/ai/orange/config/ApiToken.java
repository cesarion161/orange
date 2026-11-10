package com.ai.orange.config;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The coordinator's API token ({@code orange.api.token}, {@code ORANGE_API_TOKEN}): the bearer
 * token nodes and steering clients send to {@code /sessions}, {@code /attention},
 * {@code /mcp/steering} and {@code /brief}. With no token configured those endpoints are open,
 * like the rest of the operator API, which is only right while orange listens on localhost.
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
