package com.codexquotaguard.backend.controlplane;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Short-lived login result. Never log this result's code or persist it locally. */
public record LoginAssistanceListener(UUID id, String state, Instant startedAt, Instant expiresAt,
        Instant listeningAt, Instant receivedAt, String code, String errorCode) {
    private static final Set<String> STATES = Set.of("CONNECTING", "LISTENING", "RECEIVED",
            "CANCELLED", "TIMED_OUT", "FAILED");

    public LoginAssistanceListener {
        if (id == null || state == null || !STATES.contains(state) || startedAt == null || expiresAt == null
                || !expiresAt.isAfter(startedAt)
                || (code != null && (!"RECEIVED".equals(state) || !code.matches("[0-9]{6}")))
                || (errorCode != null && !errorCode.matches("[A-Z_]{1,80}"))) {
            throw new IllegalArgumentException("Invalid login assistance result.");
        }
    }

    @Override public String toString() {
        return "LoginAssistanceListener[id=" + id + ", state=" + state + "]";
    }
}
