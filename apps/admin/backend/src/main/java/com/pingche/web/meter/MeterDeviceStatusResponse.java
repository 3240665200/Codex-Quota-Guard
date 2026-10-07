package com.pingche.web.meter;

import java.time.Instant;
import java.util.UUID;

/** Contains no reporting token or official account credential. */
public record MeterDeviceStatusResponse(UUID deviceId, String status, Instant expiresAt,
        Instant lastSeenAt, boolean online) {
    static MeterDeviceStatusResponse from(MeterDevice device, Instant now) {
        Instant seen = device.getLastSeenAt();
        boolean online = device.getStatus() == MeterDevice.Status.ACTIVE
                && device.getExpiresAt().isAfter(now)
                && seen != null && seen.isAfter(now.minusSeconds(60));
        return new MeterDeviceStatusResponse(device.getId(), device.getStatus().name(),
                device.getExpiresAt(), seen, online);
    }
}
