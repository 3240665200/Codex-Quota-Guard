package com.pingche.web.sessionbinding;

import java.time.Instant;
import java.util.UUID;

public record SessionBindingResponse(UUID id, UUID userId, String userDisplayName, String userEmail,
        UUID deviceId, UUID enrollmentId, UUID officialAccountId, String sessionId, String status,
        String matchMethod, String verificationLevel, Instant createdAt, Instant endedAt) { }
