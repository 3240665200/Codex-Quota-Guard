package com.codexquotaguard.backend.controlplane;

import java.util.List;
import java.util.Objects;

/**
 * Account-level official Codex rate-limit metadata, deliberately distinct
 * from the product user's personal token quota.
 */
public record OfficialCodexRateLimits(
        String planType,
        List<OfficialCodexRateLimitWindow> windows,
        String fetchedAt
) {

    public OfficialCodexRateLimits {
        planType = optionalText(planType);
        windows = List.copyOf(Objects.requireNonNull(windows, "windows is required."));
        fetchedAt = optionalText(fetchedAt);
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
