package com.pingche.web.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UsagePricingCalculatorTest {
    @Test
    void excludesCachedInputAndDoesNotChargeReasoningOutputTwice() {
        var price = new UsageModelPrice("test-model", new BigDecimal("2"), new BigDecimal("8"),
                new BigDecimal("0.5"), new BigDecimal("3"), Instant.EPOCH);
        var estimate = UsagePricingCalculator.estimate(event(1000L, 300L, 100L, 200L, 150L, 1200), price);
        assertThat(estimate.estimatedCostUsd()).isEqualByComparingTo("0.00325");
        assertThat(estimate.costStatus()).isEqualTo("PRICED");
    }

    @Test
    void preservesMicroDollarPrecisionAndDistinguishesFreeFromUnknown() {
        var price = new UsageModelPrice("test-model", new BigDecimal("0.000001"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, Instant.EPOCH);
        assertThat(UsagePricingCalculator.estimate(event(1L, 0L, 0L, 0L, 0L, 1), price).estimatedCostUsd())
                .isEqualByComparingTo("0.000000000001");
        assertThat(UsagePricingCalculator.estimate(event(0L, 0L, 0L, 1L, 0L, 1), price).estimatedCostUsd())
                .isEqualByComparingTo("0");
        assertThat(UsagePricingCalculator.estimate(event(1L, 0L, 0L, 0L, 0L, 1), null).costStatus())
                .isEqualTo("MISSING_PRICE");
        assertThat(UsagePricingCalculator.estimate(event(null, null, null, null, null, 1), price).estimatedCostUsd())
                .isNull();
    }

    @Test
    void doesNotInventCostsFromInconsistentBreakdown() {
        assertThat(UsagePricingCalculator.estimate(event(100L, 80L, 30L, 20L, 0L, 120), null).costStatus())
                .isEqualTo("INVALID_BREAKDOWN");
        assertThat(UsagePricingCalculator.estimate(event(100L, 0L, 0L, 20L, 21L, 120), null).costStatus())
                .isEqualTo("INVALID_BREAKDOWN");
    }

    private CodexUsageEvent event(Long input, Long cacheRead, Long cacheWrite, Long output, Long reasoning, long total) {
        return new CodexUsageEvent("a".repeat(64), UUID.randomUUID(), null, total, Instant.EPOCH, Instant.EPOCH,
                "TOKEN_USAGE_RECORD", "test-model", "high", input, cacheRead, cacheWrite, output, reasoning);
    }
}
