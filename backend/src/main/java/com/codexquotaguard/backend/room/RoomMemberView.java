package com.codexquotaguard.backend.room;

import com.codexquotaguard.backend.quota.QuotaSnapshot;

public record RoomMemberView(
        String memberId,
        String displayName,
        boolean managedLogin,
        boolean enforcementConsent,
        QuotaSnapshot snapshot
) {
}
