package com.codexquotaguard.backend.room;

import java.util.List;

public record RoomOverview(
        boolean configured,
        String roomId,
        String name,
        List<RoomMemberView> members,
        List<String> warnings
) {

    public RoomOverview {
        members = List.copyOf(members);
        warnings = List.copyOf(warnings);
    }

    public static RoomOverview unconfigured() {
        return new RoomOverview(false, "", "", List.of(), List.of());
    }
}
