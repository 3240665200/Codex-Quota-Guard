package com.codexquotaguard.backend.room;

import java.time.Instant;

public record RoomManifest(
        int schemaVersion,
        String roomId,
        String name,
        String ownerMemberId,
        String ownerPublicKey,
        Instant createdAt
) {
}
