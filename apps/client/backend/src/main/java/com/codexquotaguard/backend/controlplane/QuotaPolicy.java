package com.codexquotaguard.backend.controlplane;

/** The central service's effective personal token limits for the current product user. */
public record QuotaPolicy(
        Long fiveHourTokenLimit,
        Long weeklyTokenLimit,
        String fiveHourSource,
        String weeklySource
) {

    public QuotaPolicy {
        requireNullableTokenLimit(fiveHourTokenLimit, "five-hour limit");
        requireNullableTokenLimit(weeklyTokenLimit, "weekly limit");
        if (isBlank(fiveHourSource) || isBlank(weeklySource)) {
            throw new IllegalArgumentException("Quota policy sources are required.");
        }
        fiveHourSource = fiveHourSource.trim();
        weeklySource = weeklySource.trim();
    }

    private static void requireNullableTokenLimit(Long value, String label) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(label + " must not be negative when configured.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
