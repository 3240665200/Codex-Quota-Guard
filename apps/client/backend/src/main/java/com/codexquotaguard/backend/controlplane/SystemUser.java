package com.codexquotaguard.backend.controlplane;

import java.util.UUID;

/**
 * The deliberately small representation of a signed-in Quota Guard user.
 *
 * <p>It is safe to return to the desktop renderer: it contains product-user
 * profile data only and never an access token, refresh token, or Codex
 * credential.</p>
 */
public record SystemUser(
        UUID id,
        String email,
        String displayName,
        String role,
        String status,
        UUID teamId,
        Integer fiveHourQuotaPercent,
        Integer weeklyQuotaPercent,
        Long fiveHourTokenLimit,
        Long weeklyTokenLimit,
        String createdAt,
        String updatedAt
) {

    public SystemUser {
        if (id == null || isBlank(email) || isBlank(displayName) || isBlank(role) || isBlank(status)) {
            throw new IllegalArgumentException("A complete system user is required.");
        }
        email = email.trim();
        displayName = displayName.trim();
        role = role.trim();
        status = status.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
