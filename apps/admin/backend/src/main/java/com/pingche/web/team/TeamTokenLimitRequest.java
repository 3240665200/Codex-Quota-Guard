package com.pingche.web.team;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Complete replacement; null means not configured, not unlimited. */
public record TeamTokenLimitRequest(
        @Min(0) @Max(1_000_000_000_000L) Long fiveHourTokenLimit,
        @Min(0) @Max(1_000_000_000_000L) Long weeklyTokenLimit,
        Long version
) { }
