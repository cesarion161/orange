package com.ai.orange.webhook;

import com.ai.orange.github.GithubProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Verifies the X-Hub-Signature-256 header GitHub sends with each webhook delivery.
 * Wraps the request so downstream handlers can still read the body.
 */
public class GithubWebhookHmacFilter extends OncePerRequestFilter {

    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String HMAC_ALG = "HmacSHA256";

    private final String secret;
    private final String path;

    public GithubWebhookHmacFilter(GithubProperties props) {
        this.secret = props.webhookSecret();
        this.path = props.webhookPath();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (secret.isBlank()) {
            response.sendError(HttpStatus.SERVICE_UNAVAILABLE.value(),
                    "Webhook secret not configured");
            return;
        }

        String header = request.getHeader(SIGNATURE_HEADER);
        if (header == null || !header.startsWith(SIGNATURE_PREFIX)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Missing signature");
            return;
        }

        CachedBodyHttpServletRequest wrapped = new CachedBodyHttpServletRequest(request);
        byte[] body = wrapped.body();

        byte[] expected;
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALG));
            expected = mac.doFinal(body);
        } catch (Exception e) {
            response.sendError(HttpStatus.INTERNAL_SERVER_ERROR.value(), "HMAC failure");
            return;
        }

        byte[] supplied;
        try {
            supplied = HexFormat.of().parseHex(header.substring(SIGNATURE_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Bad signature encoding");
            return;
        }

        if (!MessageDigest.isEqual(expected, supplied)) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Bad signature");
            return;
        }

        chain.doFilter(wrapped, response);
    }
}
