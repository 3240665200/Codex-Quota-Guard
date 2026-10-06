package com.pingche.web.codex;

import com.pingche.web.user.User;

/**
 * Safe desktop-client bootstrap payload. A null codexAccount is an expected
 * state and means an administrator has not yet assigned an account.
 */
public record ClientAccountOverviewResponse(
        boolean assigned,
        ClientSystemUserResponse user,
        ManagedCodexAccountResponse codexAccount
) {

    static ClientAccountOverviewResponse unassigned(User user) {
        return new ClientAccountOverviewResponse(false, ClientSystemUserResponse.from(user), null);
    }

    static ClientAccountOverviewResponse assigned(User user, CodexAccount account) {
        return new ClientAccountOverviewResponse(
                true,
                ClientSystemUserResponse.from(user),
                ManagedCodexAccountResponse.from(account)
        );
    }
}
