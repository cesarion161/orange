package com.ai.orange.api;

import jakarta.validation.constraints.NotBlank;

public record PlanRequest(@NotBlank String description) {
}
