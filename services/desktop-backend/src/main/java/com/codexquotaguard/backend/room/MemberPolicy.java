package com.codexquotaguard.backend.room;

import java.util.List;

public record MemberPolicy(String memberId, boolean enabled, List<QuotaRule> rules) {

    public MemberPolicy {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }
}
