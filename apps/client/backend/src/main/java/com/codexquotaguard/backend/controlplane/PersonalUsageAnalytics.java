package com.codexquotaguard.backend.controlplane;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Server-calculated aggregates for the authenticated product user only. */
public record PersonalUsageAnalytics(Instant from, Instant to, String currency, String timezone,
        String granularity, Stats summary, List<Trend> trend) {
    public record Stats(long eventCount, long totalTokens, Long inputTokens, Long cachedInputTokens,
            Long cacheWriteInputTokens, Long outputTokens, Long reasoningOutputTokens,
            long breakdownEventCount, long costedEventCount, BigDecimal estimatedCostUsd,
            BigDecimal cacheHitRatePercent) { }
    public record Trend(Instant bucket, Stats stats) { }
}
