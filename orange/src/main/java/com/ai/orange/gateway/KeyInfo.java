package com.ai.orange.gateway;

import java.math.BigDecimal;

/** What LiteLLM reports about a virtual key: its spend so far (USD) and whether it is blocked. */
public record KeyInfo(BigDecimal spend, boolean blocked) {}
