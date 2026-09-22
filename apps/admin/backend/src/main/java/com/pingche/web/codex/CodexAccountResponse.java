package com.pingche.web.codex;

import java.time.Instant;
import java.util.UUID;

public record CodexAccountResponse(
        UUID id,
        String email,
        String displayName,
        CodexAccount.Status status,
        boolean hasCredential,
        Instant createdAt,
        Instant updatedAt
) {
    public static CodexAccountResponse from(CodexAccount account) {
        return new CodexAccountResponse(
                account.getId(),
                account.getEmail(),
                account.getDisplayName(),
                account.getStatus(),
                true,
                account.getCreatedAt(),
                account.getUpdatedAt()
        );
    }
}
