package com.ai.orange.harness;

/**
 * How one run authenticates to its model provider. Both variants hold a secret, so both
 * redact it from {@code toString()}: a credential must never reach a log line, an event
 * payload or an exception message.
 */
public sealed interface Credentials {

    /** The user's Max-plan token from {@code claude setup-token}; only the {@code claude} binary may receive it. */
    record Subscription(String oauthToken) implements Credentials {
        @Override
        public String toString() {
            return "Subscription[***]";
        }
    }

    /** A LiteLLM virtual key minted for this run only. */
    record Gateway(String baseUrl, String virtualKey) implements Credentials {
        @Override
        public String toString() {
            return "Gateway[baseUrl=" + baseUrl + ", key=***]";
        }
    }
}
