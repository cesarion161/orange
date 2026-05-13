package com.ai.orange.api;

import java.util.Map;

public record CompleteClaimRequest(String summary, Map<String, Object> artifacts) {
}
