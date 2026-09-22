package com.pingche.web.admin;

import com.pingche.web.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdminUserCreateRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 12, max = 128) String password,
        @NotBlank @Size(max = 120) String displayName,
        User.Role role,
        User.Status status
) {
    public AdminUserCreateRequest {
        email = email == null ? null : email.trim();
        displayName = displayName == null ? null : displayName.trim();
    }
}
