package com.pingche.web.sessionbinding;

import java.time.Instant;
import java.util.UUID;

public record SessionBindingAttemptResponse(UUID id, UUID deviceId, UUID enrollmentId, UUID officialAccountId,
        String status, int queuePosition, boolean loginAllowed, String candidateSessionId,
        String candidateDisplayName, String candidatePlatform, Instant expiresAt, String errorCode,
        boolean quarantined, SessionBindingResponse binding, String queueState, Instant retryAt) { }
