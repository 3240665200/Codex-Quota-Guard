package com.codexquotaguard.backend.controlplane;

import java.util.List;

/**
 * Safe quota projection for the current user and their current team.
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
        String blockReason,
        List<TeamMemberUsage> teamMembers
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
        // A missing field means the central service does not support this projection yet.
        teamMembers = teamMembers == null ? null : List.copyOf(teamMembers);
    }

    public PersonalQuotaOverview(SystemUser user, QuotaTeam team, TeamQuotaOverview teamQuota,
            QuotaPolicy policy, QuotaUsage usage, boolean allowed, String blockReason) {
        this(user, team, teamQuota, policy, usage, allowed, blockReason, null);
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
