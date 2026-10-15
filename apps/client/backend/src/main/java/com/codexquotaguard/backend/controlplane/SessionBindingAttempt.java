package com.codexquotaguard.backend.controlplane;

import java.time.Instant;
import java.util.UUID;

public record SessionBindingAttempt(UUID id, UUID deviceId, UUID enrollmentId, UUID officialAccountId,
        String status, int queuePosition, boolean loginAllowed, String candidateSessionId,
        String candidateDisplayName, String candidatePlatform, Instant expiresAt, String errorCode,
        boolean quarantined, OfficialSessionBinding binding, String queueState, Instant retryAt) {
    public SessionBindingAttempt(UUID id, UUID deviceId, UUID enrollmentId, UUID officialAccountId,
            String status, int queuePosition, boolean loginAllowed, String candidateSessionId,
            String candidateDisplayName, String candidatePlatform, Instant expiresAt, String errorCode,
            boolean quarantined, OfficialSessionBinding binding) {
        this(id,deviceId,enrollmentId,officialAccountId,status,queuePosition,loginAllowed,candidateSessionId,
                candidateDisplayName,candidatePlatform,expiresAt,errorCode,quarantined,binding,null,null);
    }
    public SessionBindingAttempt {
        if(queueState!=null && !java.util.Set.of("IDLE","SETTLING","RETRYING","PREPARING","ACTIVE").contains(queueState))
            throw new IllegalArgumentException("Invalid binding queue state.");
    }
}
