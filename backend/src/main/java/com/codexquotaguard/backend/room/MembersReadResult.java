package com.codexquotaguard.backend.room;

import java.util.List;
import java.util.Map;

public record MembersReadResult(Map<String, VerifiedMember> members, List<String> warnings) {

    public MembersReadResult {
        members = Map.copyOf(members);
        warnings = List.copyOf(warnings);
    }
}
