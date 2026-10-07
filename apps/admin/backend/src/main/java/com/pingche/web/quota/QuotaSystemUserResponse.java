package com.pingche.web.quota;

import com.pingche.web.user.User;
import java.util.UUID;

/** The signed-in product user, never an upstream Codex account. */
public record QuotaSystemUserResponse(UUID id, String email, String displayName,
        String role, String status) {
    public static QuotaSystemUserResponse from(User user) {
        return new QuotaSystemUserResponse(user.getId(), user.getEmail(), user.getDisplayName(),
                user.getRole().name(), user.getStatus().name());
    }
}
