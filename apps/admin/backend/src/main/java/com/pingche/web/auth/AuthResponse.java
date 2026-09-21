package com.pingche.web.auth;

import com.pingche.web.user.UserResponse;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        String refreshToken,
        long refreshExpiresIn,
        UserResponse user
) {
}
