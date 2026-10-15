package com.codexquotaguard.backend.controlplane;

import java.util.UUID;

/** Safe metadata only; permission is freshly resolved by the central service. */
public record ClientAccess(boolean allowed, UUID bindingId, String reason) {
    public ClientAccess {
        if (allowed ? bindingId == null || reason != null : bindingId != null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Invalid access decision");
        }
    }
}
