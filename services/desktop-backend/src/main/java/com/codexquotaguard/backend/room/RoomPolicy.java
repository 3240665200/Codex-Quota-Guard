package com.codexquotaguard.backend.room;

import java.time.Instant;
import java.util.Map;

public record RoomPolicy(
        int schemaVersion,
        String roomId,
        long revision,
        Instant updatedAt,
        Map<String, MemberPolicy> members
) {

    public RoomPolicy {
        members = members == null ? Map.of() : Map.copyOf(members);
    }
}
