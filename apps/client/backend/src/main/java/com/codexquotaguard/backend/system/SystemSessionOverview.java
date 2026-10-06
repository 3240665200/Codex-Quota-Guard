package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.controlplane.SystemUser;

/** Safe renderer-facing state for the product-user session. */
public record SystemSessionOverview(
        boolean authenticated,
        SystemUser user
) {

    public SystemSessionOverview {
        if (authenticated != (user != null)) {
            throw new IllegalArgumentException("System session state is inconsistent.");
        }
    }

    static SystemSessionOverview signedOut() {
        return new SystemSessionOverview(false, null);
    }

    static SystemSessionOverview signedIn(SystemUser user) {
        return new SystemSessionOverview(true, user);
    }
}
