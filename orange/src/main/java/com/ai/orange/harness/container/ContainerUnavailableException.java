package com.ai.orange.harness.container;

import java.io.IOException;

/**
 * Thrown when docker itself cannot be used: no binary, no daemon, no network. Nothing an agent did,
 * and nothing a retry fixes: a person starts Docker or builds the image.
 */
public class ContainerUnavailableException extends IOException {

    public ContainerUnavailableException(String message) {
        super(message);
    }

    public ContainerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
