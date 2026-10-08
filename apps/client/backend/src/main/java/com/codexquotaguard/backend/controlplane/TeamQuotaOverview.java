package com.codexquotaguard.backend.controlplane;

import java.time.Instant;

/** Safe aggregate for the user's current team, separate from personal usage. */
public record TeamQuotaOverview(
        String id,
        String name,
        String status,
        long memberCount,
        Long fiveHourTokenLimit,
        Long weeklyTokenLimit,
        Long totalTokenLimit,
        long fiveHourUsedTokens,
        long weeklyUsedTokens,
        long totalUsedTokens,
        Long fiveHourRemainingTokens,
        Long weeklyRemainingTokens,
        Long totalRemainingTokens,
        Instant measuredAt
) {
    public TeamQuotaOverview {
        if (id == null || id.isBlank() || name == null || name.isBlank()
                || status == null || status.isBlank() || memberCount < 0
                || fiveHourUsedTokens < 0 || weeklyUsedTokens < 0 || totalUsedTokens < 0
                || isNegative(fiveHourTokenLimit) || isNegative(weeklyTokenLimit)
                || isNegative(totalTokenLimit)
                || isNegative(fiveHourRemainingTokens) || isNegative(weeklyRemainingTokens)
                || isNegative(totalRemainingTokens)) {
            throw new IllegalArgumentException("A valid team quota overview is required.");
        }
    }

    private static boolean isNegative(Long value) {
        return value != null && value < 0;
    }
}
