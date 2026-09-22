package com.pingche.web.admin;

import com.pingche.web.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record AdminUserUpdateRequest(
        @Email @Size(max = 320) String email,
        @Size(max = 120) String displayName,
        @Size(min = 12, max = 128) String password,
        User.Status status,
        User.Role role
) {
    public AdminUserUpdateRequest {
        email = email == null ? null : email.trim();
        displayName = displayName == null ? null : displayName.trim();
    }
}
