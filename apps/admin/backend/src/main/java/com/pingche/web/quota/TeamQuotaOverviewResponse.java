package com.pingche.web.quota;

import java.time.Instant;
import java.util.UUID;

public record TeamQuotaOverviewResponse(
        UUID id,
        String name,
        String status,
        long memberCount,
        Long fiveHourTokenLimit,
        Long weeklyTokenLimit,
        Long totalTokenLimit,
        long fiveHourUsedTokens,
        long weeklyUsedTokens,
        long totalUsedTokens,
        Long fiveHourRemainingTokens,
        Long weeklyRemainingTokens,
        Long totalRemainingTokens,
        Instant measuredAt,
        UsdQuotaService.Scope billing
) { }
