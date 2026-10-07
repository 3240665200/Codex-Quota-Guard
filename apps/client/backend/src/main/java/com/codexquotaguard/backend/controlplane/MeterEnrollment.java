package com.codexquotaguard.backend.controlplane;

import java.util.UUID;

/** Device-scoped reporting credential; never serialize this to the WebView. */
public record MeterEnrollment(UUID deviceId, String deviceToken, String expiresAt) {
    public MeterEnrollment {
        if (deviceId == null || deviceToken == null || !deviceToken.matches("[A-Za-z0-9_-]{43}")
                || expiresAt == null || expiresAt.isBlank()) {
            throw new IllegalArgumentException("A valid meter enrollment is required.");
        }
    }

    @Override public String toString() {
        return "MeterEnrollment[deviceId=" + deviceId + ", deviceToken=REDACTED]";
    }
}
