package com.pingche.web.quota;

import com.pingche.web.user.User;
import java.time.Instant;
import java.util.UUID;

public record TeamMemberUsageResponse(
        UUID userId,
        String displayName,
        User.Status status,
        long fiveHourUsedTokens,
        long weeklyUsedTokens,
        long totalUsedTokens,
        Instant measuredAt,
        Long fiveHourTokenLimit,
        Long weeklyTokenLimit,
        Long fiveHourRemainingTokens,
        Long weeklyRemainingTokens
) { }
