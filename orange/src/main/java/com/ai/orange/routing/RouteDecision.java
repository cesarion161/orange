package com.ai.orange.routing;

import java.math.BigDecimal;

/** Where and how one run executes. Stored on the run so its cost and outcome can be judged later. */
public record RouteDecision(String tier, String model, Effort effort, String harness, AuthMode auth,
                            BigDecimal budgetUsd) {}
