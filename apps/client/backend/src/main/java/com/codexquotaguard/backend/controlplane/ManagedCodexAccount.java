package com.codexquotaguard.backend.controlplane;

import java.util.UUID;

/** Metadata about an administrator-assigned Codex account; no credential fields exist here. */
public record ManagedCodexAccount(
        UUID id,
        String displayName,
        String status,
        String updatedAt
) {

    public ManagedCodexAccount {
        if (id == null || displayName == null || displayName.isBlank() || status == null || status.isBlank()) {
            throw new IllegalArgumentException("A complete managed Codex account is required.");
        }
        displayName = displayName.trim();
        status = status.trim();
    }
}
