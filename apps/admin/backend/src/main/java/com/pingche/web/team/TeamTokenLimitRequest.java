package com.pingche.web.team;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;

/** Complete replacement; a null optional window or total cap imposes no limit. */
public record TeamTokenLimitRequest(
        @Min(0) @Max(1_000_000_000_000L) Long fiveHourTokenLimit,
        @Min(0) @Max(1_000_000_000_000L) Long weeklyTokenLimit,
        @Min(0) @Max(1_000_000_000_000L) Long totalTokenLimit,
        UUID officialAccountId,
        Long version
) { }
