package com.codexquotaguard.backend.system;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Only the refresh credential and its original local login deadline are persisted. */
public record SavedSession(String refreshToken, UUID userId, Instant loginAt, Instant expiresAt) {

    static final int MAX_REFRESH_TOKEN_BYTES = 4096;
    static final Duration MAX_LOGIN_LIFETIME = Duration.ofDays(7);

    public SavedSession {
        boolean valid = refreshToken != null && !refreshToken.isBlank()
                && refreshToken.length() <= MAX_REFRESH_TOKEN_BYTES
                && StandardCharsets.UTF_8.newEncoder().canEncode(refreshToken)
                && refreshToken.getBytes(StandardCharsets.UTF_8).length <= MAX_REFRESH_TOKEN_BYTES
                && userId != null && loginAt != null && expiresAt != null
                && expiresAt.isAfter(loginAt);
        if (valid) {
            try {
                valid = !expiresAt.isAfter(loginAt.plus(MAX_LOGIN_LIFETIME));
            } catch (RuntimeException invalidTime) {
                valid = false;
            }
        }
        if (!valid) {
            throw new IllegalArgumentException("A valid saved session is required.");
        }
    }

    @Override
    public String toString() {
        return "SavedSession[redacted]";
    }
}
