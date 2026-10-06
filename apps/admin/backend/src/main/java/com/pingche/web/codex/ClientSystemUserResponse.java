package com.pingche.web.codex;

import com.pingche.web.user.User;
import java.util.UUID;

/**
 * The caller's own system account summary. This is intentionally distinct from
 * an upstream Codex account.
 */
public record ClientSystemUserResponse(
        UUID id,
        String email,
        String displayName,
        String role,
        String status
) {

    public static ClientSystemUserResponse from(User user) {
        return new ClientSystemUserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getRole().name(),
                user.getStatus().name()
        );
    }
}
