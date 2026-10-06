package com.pingche.web.codex;

import java.time.Instant;
import java.util.UUID;

/** Explicit allowlist: no upstream access/refresh tokens, raw RPC errors or server paths. */
public record CodexAuthorizationResponse(
        UUID id,
        Status status,
        String verificationUrl,
        String userCode,
        Instant expiresAt,
        CodexAccountResponse account,
        String errorCode,
        String message,
        CodexLoginMode mode,
        String authUrl
) {
    public enum Status { STARTING, PENDING, SUCCEEDED, FAILED, CANCELLED, EXPIRED }

    @Override
    public String toString() { return "CodexAuthorizationResponse[id=" + id + ", status=" + status + "]"; }
}
