package com.ai.orange.mcp;

import com.ai.orange.run.Run;
import com.ai.orange.run.RunRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code /mcp/worker}. The bearer token must belong to a run that is still
 * {@code running}; anything else (no token, an unknown one, a finished run's) is a 401 and never
 * reaches the MCP server. The resolved run travels on as a request attribute.
 */
public class WorkerTokenFilter extends OncePerRequestFilter {

    public static final String RUN_ATTRIBUTE = WorkerTokenFilter.class.getName() + ".run";

    private final RunRepository runs;

    public WorkerTokenFilter(RunRepository runs) {
        this.runs = runs;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Run> run = bearer(request).flatMap(token -> runs.findRunningByTokenHash(RunTokens.hash(token)));
        if (run.isEmpty()) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"invalid or inactive run token\"}");
            return;
        }
        request.setAttribute(RUN_ATTRIBUTE, run.get());
        chain.doFilter(request, response);
    }

    private static Optional<String> bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return Optional.empty();
        String token = header.substring(7).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
