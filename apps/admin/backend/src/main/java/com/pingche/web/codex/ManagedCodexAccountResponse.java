package com.pingche.web.codex;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimal account metadata that can safely be shown to a system user. It never
 * contains an upstream email, encrypted credential fields, or credential state.
 */
public record ManagedCodexAccountResponse(
        UUID id,
        String displayName,
        CodexAccount.Status status,
        Instant updatedAt
) {

    public static ManagedCodexAccountResponse from(CodexAccount account) {
        return new ManagedCodexAccountResponse(
                account.getId(),
                account.getDisplayName(),
                account.getStatus(),
                account.getUpdatedAt()
        );
    }
}
