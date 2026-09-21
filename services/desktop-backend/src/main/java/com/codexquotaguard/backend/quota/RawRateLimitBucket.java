package com.codexquotaguard.backend.quota;

/** Raw bucket fields returned by Codex app-server. */
public record RawRateLimitBucket(
        String limitId,
        String limitName,
        RawRateLimitWindow primary,
        RawRateLimitWindow secondary,
        String planType,
        boolean spendControlReached
) {
}
