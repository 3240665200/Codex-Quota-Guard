package com.pingche.web.meter;

import java.time.Instant;
import java.util.UUID;

/** The raw meter token appears only once, in this no-store enrollment response. */
public record MeterEnrollmentResponse(UUID deviceId, String deviceToken, Instant expiresAt) {
    @Override public String toString() {
        return "MeterEnrollmentResponse[deviceId=" + deviceId + ", deviceToken=REDACTED]";
    }
}
