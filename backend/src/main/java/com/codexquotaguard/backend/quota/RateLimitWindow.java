package com.codexquotaguard.backend.quota;

import java.time.Instant;

public record RateLimitWindow(
        double usedPercent,
        double remainingPercent,
        long windowDurationMins,
        Instant resetsAt
) {
}
