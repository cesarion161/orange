package com.ai.orange.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Per-run bearer tokens for the worker MCP endpoint. A token is 32 random bytes, base64url.
 * Only its SHA-256 is stored ({@code runs.mcp_token_hash}); the raw token lives in memory, in the
 * run's MCP config file and in the child process.
 */
public final class RunTokens {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RunTokens() {}

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Lowercase hex SHA-256 of the token. */
    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
