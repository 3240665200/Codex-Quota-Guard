package com.codexquotaguard.backend.controlplane;

/** Client-reported personal token totals for the central rolling windows. */
public record QuotaUsage(
        QuotaMeasurementStatus measurementStatus,
        long fiveHourUsedTokens,
        long weeklyUsedTokens,
        Long fiveHourRemainingTokens,
        Long weeklyRemainingTokens,
        String fiveHourResetsAt,
        String weeklyResetsAt,
        String measuredAt
) {

    public QuotaUsage {
        if (measurementStatus == null) {
            throw new IllegalArgumentException("Quota measurement status is required.");
        }
        requireNonNegative(fiveHourUsedTokens, "five-hour used");
        requireNonNegative(weeklyUsedTokens, "weekly used");
        requireNullableNonNegative(fiveHourRemainingTokens, "five-hour remaining");
        requireNullableNonNegative(weeklyRemainingTokens, "weekly remaining");
        fiveHourResetsAt = optionalText(fiveHourResetsAt);
        weeklyResetsAt = optionalText(weeklyResetsAt);
        measuredAt = optionalText(measuredAt);
    }

    private static void requireNonNegative(long value, String label) {
        if (value < 0) {
            throw new IllegalArgumentException(label + " must not be negative.");
        }
    }

    private static void requireNullableNonNegative(Long value, String label) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(label + " must not be negative when present.");
        }
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
