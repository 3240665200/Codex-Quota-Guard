package com.pingche.web.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class UsagePricingCalculator {
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    private UsagePricingCalculator() { }

    public record Estimate(BigDecimal estimatedCostUsd, String costStatus) { }

    public static Estimate estimate(CodexUsageEvent event, UsageModelPrice price) {
        if (event.getInputTokens() == null || event.getOutputTokens() == null
                || event.getCachedInputTokens() == null || event.getCacheWriteInputTokens() == null) {
            return new Estimate(null, "MISSING_BREAKDOWN");
        }
        long input = event.getInputTokens();
        long output = event.getOutputTokens();
        long cached = event.getCachedInputTokens();
        long written = event.getCacheWriteInputTokens();
        if (input < 0 || output < 0 || cached < 0 || written < 0
                || cached > input || written > input - cached || input + output != event.getTotalTokens()
                || (event.getReasoningOutputTokens() != null
                    && (event.getReasoningOutputTokens() < 0 || event.getReasoningOutputTokens() > output))) {
            return new Estimate(null, "INVALID_BREAKDOWN");
        }
        if (price == null) {
            return new Estimate(null, "MISSING_PRICE");
        }
        // Codex input includes cache; reasoning output is already part of output.
        BigDecimal cost = BigDecimal.valueOf(input - cached - written).multiply(price.getInputPrice())
                .add(BigDecimal.valueOf(output).multiply(price.getOutputPrice()))
                .add(BigDecimal.valueOf(cached).multiply(price.getCacheReadPrice()))
                .add(BigDecimal.valueOf(written).multiply(price.getCacheWritePrice()))
                .divide(MILLION, 12, RoundingMode.HALF_UP);
        return new Estimate(cost, "PRICED");
    }
}
