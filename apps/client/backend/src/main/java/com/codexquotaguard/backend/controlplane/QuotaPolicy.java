package com.codexquotaguard.backend.controlplane;

/** The central service's effective limits for the current managed account. */
public record QuotaPolicy(
        double fiveHourLimitPercent,
        double weeklyLimitPercent,
        String fiveHourSource,
        String weeklySource
) {

    public QuotaPolicy {
        requirePercent(fiveHourLimitPercent, "five-hour limit");
        requirePercent(weeklyLimitPercent, "weekly limit");
        if (isBlank(fiveHourSource) || isBlank(weeklySource)) {
            throw new IllegalArgumentException("Quota policy sources are required.");
        }
        fiveHourSource = fiveHourSource.trim();
        weeklySource = weeklySource.trim();
    }

    private static void requirePercent(double value, String label) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new IllegalArgumentException(label + " must be a percentage.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
