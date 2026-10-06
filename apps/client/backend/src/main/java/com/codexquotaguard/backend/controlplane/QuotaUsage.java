package com.codexquotaguard.backend.controlplane;

/** Measured quota values, with nullable fields for an intentionally unmeasured state. */
public record QuotaUsage(
        QuotaMeasurementStatus measurementStatus,
        Double fiveHourUsedPercent,
        Double weeklyUsedPercent,
        Double fiveHourRemainingPercent,
        Double weeklyRemainingPercent,
        String fiveHourResetsAt,
        String weeklyResetsAt,
        String measuredAt
) {

    public QuotaUsage {
        if (measurementStatus == null) {
            throw new IllegalArgumentException("Quota measurement status is required.");
        }
        requireNullablePercent(fiveHourUsedPercent, "five-hour used");
        requireNullablePercent(weeklyUsedPercent, "weekly used");
        requireNullablePercent(fiveHourRemainingPercent, "five-hour remaining");
        requireNullablePercent(weeklyRemainingPercent, "weekly remaining");
        fiveHourResetsAt = optionalText(fiveHourResetsAt);
        weeklyResetsAt = optionalText(weeklyResetsAt);
        measuredAt = optionalText(measuredAt);
        if (measurementStatus == QuotaMeasurementStatus.NOT_MEASURED
                && (fiveHourUsedPercent != null || weeklyUsedPercent != null
                || fiveHourRemainingPercent != null || weeklyRemainingPercent != null)) {
            throw new IllegalArgumentException("Unmeasured quota cannot contain usage values.");
        }
        if (measurementStatus == QuotaMeasurementStatus.CURRENT
                && (fiveHourUsedPercent == null || weeklyUsedPercent == null
                || fiveHourRemainingPercent == null || weeklyRemainingPercent == null)) {
            throw new IllegalArgumentException("Current quota requires all usage values.");
        }
    }

    private static void requireNullablePercent(Double value, String label) {
        if (value != null && (!Double.isFinite(value) || value < 0 || value > 100)) {
            throw new IllegalArgumentException(label + " must be a percentage when present.");
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
