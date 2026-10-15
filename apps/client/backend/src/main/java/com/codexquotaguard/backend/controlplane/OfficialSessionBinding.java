package com.codexquotaguard.backend.controlplane;

import java.time.Instant;
import java.util.UUID;

/** Safe mapping metadata only; never contains an official credential. */
public record OfficialSessionBinding(UUID id, UUID userId, String userDisplayName, String userEmail,
        UUID deviceId, UUID enrollmentId, UUID officialAccountId, String sessionId, String status,
        String matchMethod, String verificationLevel, Instant createdAt, Instant endedAt) { }
