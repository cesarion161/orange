package com.ai.orange.api;

public record FailClaimRequest(String reason, Boolean retryable) {
}
