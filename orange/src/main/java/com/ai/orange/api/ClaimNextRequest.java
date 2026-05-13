package com.ai.orange.api;

import jakarta.validation.constraints.NotBlank;

public record ClaimNextRequest(@NotBlank String role, @NotBlank String workerId) {
}
