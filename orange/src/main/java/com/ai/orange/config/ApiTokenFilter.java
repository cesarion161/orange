package com.ai.orange.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Guards the endpoints nodes and steering clients use ({@code /sessions}, {@code /attention},
 * {@code /mcp/steering}) with the coordinator's API token ({@code orange.api.token},
 * {@code ORANGE_API_TOKEN}) as a bearer token. With no token configured they are open, like the
 * rest of the operator API, which is only right while orange listens on localhost.
 */
public class ApiTokenFilter extends OncePerRequestFilter {

    public static final List<String> GUARDED = List.of("/sessions", "/attention", "/mcp/steering");

    private final byte[] token;

    public ApiTokenFilter(String token) {
        this.token = token == null || token.isBlank() ? null : token.strip().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (token == null) return true;
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return GUARDED.stream().noneMatch(p -> path.equals(p) || path.startsWith(p + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        boolean ok = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                && MessageDigest.isEqual(header.substring(7).trim().getBytes(StandardCharsets.UTF_8), token);
        if (!ok) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or wrong API token\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
