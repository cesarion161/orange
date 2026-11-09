package com.ai.orange.gateway;

/** A LiteLLM admin call failed. The message never contains a key. */
public class GatewayException extends RuntimeException {

    public GatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
