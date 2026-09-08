package com.codexquotaguard.backend.quota;

public record RateLimitBucket(
        String limitId,
        String limitName,
        RateLimitWindow primary,
        RateLimitWindow secondary,
        String planType,
        boolean spendControlReached
) {
}
