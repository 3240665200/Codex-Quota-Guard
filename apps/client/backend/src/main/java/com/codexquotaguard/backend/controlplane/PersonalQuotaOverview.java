package com.codexquotaguard.backend.controlplane;

/**
 * Safe, current-user-only quota projection returned by the central service.
 * It deliberately contains neither Codex credentials nor any raw upstream
 * authentication payload.
 */
public record PersonalQuotaOverview(
        SystemUser user,
        QuotaTeam team,
        TeamQuotaOverview teamQuota,
        QuotaPolicy policy,
        QuotaUsage usage,
        boolean allowed,
        String blockReason
) {

    public PersonalQuotaOverview {
        if (user == null || policy == null || usage == null) {
            throw new IllegalArgumentException("A complete personal quota overview is required.");
        }
        if (allowed && teamQuota == null && policy.weeklyTokenLimit() == null) {
            throw new IllegalArgumentException("An allowed user needs a personal or team weekly limit.");
        }
        if (allowed && teamQuota != null && teamQuota.weeklyTokenLimit() == null) {
            throw new IllegalArgumentException("An allowed team needs a weekly limit.");
        }
        if (teamQuota != null && (team == null || !teamQuota.id().equals(team.id()))) {
            throw new IllegalArgumentException("Team quota does not match the current team.");
        }
        blockReason = optionalText(blockReason);
    }

    public PersonalQuotaOverview(SystemUser user, QuotaTeam team, QuotaPolicy policy,
            QuotaUsage usage, boolean allowed, String blockReason) {
        this(user, team, null, policy, usage, allowed, blockReason);
    }

    private static String optionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
