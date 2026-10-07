package com.pingche.web.user;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Null inherits the team's token cap. */
public record UserTokenLimitRequest(
        @Min(0) @Max(1_000_000_000_000L) Long fiveHourTokenLimit,
        @Min(0) @Max(1_000_000_000_000L) Long weeklyTokenLimit
) { }
