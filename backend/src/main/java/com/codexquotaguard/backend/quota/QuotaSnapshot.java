package com.codexquotaguard.backend.quota;

import java.time.Instant;
import java.util.List;

public record QuotaSnapshot(
        int schemaVersion,
        String roomId,
        String memberId,
        String displayName,
        Instant capturedAt,
        Instant publishedAt,
        String source,
        boolean authenticated,
        String planType,
        List<RateLimitBucket> buckets,
        boolean managedLogin
) {

    public QuotaSnapshot {
        buckets = List.copyOf(buckets);
    }
}
