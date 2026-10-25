package com.codexquotaguard.backend.controlplane;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import java.time.Instant;
import java.util.UUID;

/**
 * Safe member metadata and current-team contribution, without credentials or email.
 * Personal limits and remaining tokens describe the member's own quota windows,
 * independently of their contribution to the current team.
 */
public record TeamMemberUsage(
        UUID userId,
        String displayName,
        String status,
        @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) long fiveHourUsedTokens,
        @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) long weeklyUsedTokens,
        @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) long totalUsedTokens,
        @JsonProperty(required = true) Long fiveHourTokenLimit,
        @JsonProperty(required = true) Long weeklyTokenLimit,
        @JsonProperty(required = true) Long fiveHourRemainingTokens,
        @JsonProperty(required = true) Long weeklyRemainingTokens,
        Instant measuredAt
) {
    public TeamMemberUsage {
        if (userId == null || displayName == null || displayName.isBlank()
                || !("ACTIVE".equals(status) || "DISABLED".equals(status))
                || fiveHourUsedTokens < 0 || weeklyUsedTokens < 0 || totalUsedTokens < 0
                || invalidPersonalWindow(fiveHourTokenLimit, fiveHourRemainingTokens)
                || invalidPersonalWindow(weeklyTokenLimit, weeklyRemainingTokens)) {
            throw new IllegalArgumentException("A valid team member usage summary is required.");
        }
        displayName = displayName.trim();
    }

    private static boolean invalidPersonalWindow(Long limit, Long remaining) {
        if (limit == null) {
            return remaining != null;
        }
        return limit < 0 || remaining == null || remaining < 0 || remaining > limit;
    }
}
