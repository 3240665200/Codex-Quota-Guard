package com.pingche.web.quota;

import java.time.Instant;
import java.util.UUID;

public record OfficialAccountResponse(
        UUID id,
        String label,
        long estimatedTotalTokens,
        String reporterEmail,
        OfficialQuotaSnapshotResponse quota,
        Instant createdAt,
        Instant updatedAt,
        long version
) { }
