package com.codexquotaguard.backend.codex;

public final class OfficialCodexRateLimitsUnavailableException extends RuntimeException {
    public OfficialCodexRateLimitsUnavailableException() {
        super("Official Codex rate limits are unavailable");
    }
}
