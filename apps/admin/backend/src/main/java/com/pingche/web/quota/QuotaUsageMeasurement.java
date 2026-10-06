package com.pingche.web.quota;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * A measured per-system-user usage snapshot produced by a trusted controlled
 * execution path. It is deliberately not an HTTP request DTO: the upstream
 * Codex account rate-limit response is aggregate account data and must not be
 * represented as an individual system user's usage.
 */
public record QuotaUsageMeasurement(
        BigDecimal fiveHourUsedPercent,
        BigDecimal weeklyUsedPercent,
        Instant fiveHourResetsAt,
        Instant weeklyResetsAt
) {

    public QuotaUsageMeasurement {
        Objects.requireNonNull(fiveHourUsedPercent, "fiveHourUsedPercent");
        Objects.requireNonNull(weeklyUsedPercent, "weeklyUsedPercent");
    }
}
