package com.ai.orange.api;

import jakarta.validation.constraints.NotBlank;

public record TaskCreateRequest(
        @NotBlank String title,
        String description,
        String role,
        String pipeline,
        Integer priority) {
}
