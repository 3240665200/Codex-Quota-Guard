package com.codexquotaguard.backend.controlplane;

/** A safe, account-level rate-limit window reported by the official Codex service. */
public record OfficialCodexRateLimitWindow(
        String limitId,
        String slot,
        long windowDurationMins,
        double usedPercent,
        String resetsAt
) {

    public OfficialCodexRateLimitWindow {
        if (isBlank(limitId) || isBlank(slot) || windowDurationMins <= 0
                || !Double.isFinite(usedPercent) || usedPercent < 0 || usedPercent > 100) {
            throw new IllegalArgumentException("A complete official rate-limit window is required.");
        }
        limitId = limitId.trim();
        slot = slot.trim();
        resetsAt = optionalText(resetsAt);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
