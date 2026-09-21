package com.codexquotaguard.backend.account;

import com.codexquotaguard.backend.quota.QuotaSnapshot;

/** Account information that is safe to return to the desktop renderer. */
public record AccountOverview(
        boolean authenticated,
        String planType,
        QuotaSnapshot snapshot
) {

    public AccountOverview {
        planType = planType == null ? "" : planType.trim();
        if (snapshot == null) {
            throw new IllegalArgumentException("Quota snapshot is required.");
        }
    }
}
