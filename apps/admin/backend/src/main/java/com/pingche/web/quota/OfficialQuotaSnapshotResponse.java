package com.pingche.web.quota;

import java.time.Instant;
import java.util.List;

public record OfficialQuotaSnapshotResponse(
        String planType,
        List<OfficialQuotaSnapshotRequest.Window> windows,
        Instant fetchedAt,
        Instant receivedAt
) { }
