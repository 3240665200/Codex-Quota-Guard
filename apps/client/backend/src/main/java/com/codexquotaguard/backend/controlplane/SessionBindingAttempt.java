package com.codexquotaguard.backend.controlplane;

import java.time.Instant;
import java.util.UUID;

public record SessionBindingAttempt(UUID id, UUID deviceId, UUID enrollmentId, UUID officialAccountId,
        String status, int queuePosition, boolean loginAllowed, String candidateSessionId,
        String candidateDisplayName, String candidatePlatform, Instant expiresAt, String errorCode,
        boolean quarantined, OfficialSessionBinding binding) { }
