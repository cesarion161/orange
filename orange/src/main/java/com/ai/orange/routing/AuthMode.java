package com.ai.orange.routing;

/**
 * How a run's harness authenticates to the model provider.
 *
 * <ul>
 *   <li>{@link #SUBSCRIPTION}: Claude Code talks to Anthropic directly with the user's Max-plan
 *       token ({@code claude setup-token}). The token is only ever given to the {@code claude}
 *       binary; Anthropic's terms forbid using it from any other tool, LiteLLM included.</li>
 *   <li>{@link #GATEWAY}: the run gets a short-lived LiteLLM virtual key with its own budget.</li>
 * </ul>
 */
public enum AuthMode {
    SUBSCRIPTION, GATEWAY
}
