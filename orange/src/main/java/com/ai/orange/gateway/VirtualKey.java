package com.ai.orange.gateway;

import java.time.Instant;

/**
 * A LiteLLM virtual key minted for one run. {@code alias} is safe to store and log;
 * {@code key} is the secret and is redacted from {@code toString()}.
 *
 * @param expiresAt when LiteLLM stops accepting the key; null if it reported no expiry
 */
public record VirtualKey(String alias, String key, Instant expiresAt) {

    @Override
    public String toString() {
        return "VirtualKey[alias=" + alias + ", key=***, expiresAt=" + expiresAt + "]";
    }
}
