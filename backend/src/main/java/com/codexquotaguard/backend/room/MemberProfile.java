package com.codexquotaguard.backend.room;

import java.time.Instant;

public record MemberProfile(
        int schemaVersion,
        String roomId,
        String memberId,
        String displayName,
        String publicKey,
        Instant joinedAt,
        boolean managedLogin,
        boolean enforcementConsent
) {
}
