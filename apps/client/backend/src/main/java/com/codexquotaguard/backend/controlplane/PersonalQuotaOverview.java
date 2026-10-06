package com.codexquotaguard.backend.controlplane;

/**
 * Safe, current-user-only quota projection returned by the central service.
 * It deliberately contains neither Codex credentials nor any raw upstream
 * authentication payload.
 */
public record PersonalQuotaOverview(
        boolean assigned,
        SystemUser user,
        ManagedCodexAccount codexAccount,
        QuotaTeam team,
        QuotaPolicy policy,
        QuotaUsage usage,
        boolean allowed,
        String blockReason
) {

    public PersonalQuotaOverview {
        if (user == null || policy == null || usage == null) {
            throw new IllegalArgumentException("A complete personal quota overview is required.");
        }
        if (assigned != (codexAccount != null)) {
            throw new IllegalArgumentException("Codex account assignment state is inconsistent.");
        }
        if (usage.measurementStatus() == QuotaMeasurementStatus.NOT_MEASURED && allowed) {
            throw new IllegalArgumentException("Unmeasured quota cannot be marked as allowed.");
        }
        blockReason = optionalText(blockReason);
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
