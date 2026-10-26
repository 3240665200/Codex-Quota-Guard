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
        long version,
        com.pingche.web.officialsession.OfficialQuotaPollingService.Response quotaPolling,
        @com.fasterxml.jackson.annotation.JsonFormat(shape = com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) java.math.BigDecimal budgetUsd,
        UsdQuotaService.Account billing
) { }
