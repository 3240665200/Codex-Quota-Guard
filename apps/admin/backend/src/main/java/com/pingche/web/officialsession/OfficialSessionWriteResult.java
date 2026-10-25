package com.pingche.web.officialsession;

/** HTTP acknowledgement only, never a claim that every official session has disappeared. */
public record OfficialSessionWriteResult(String status, String errorCode, java.time.Instant retryAt) {
    public OfficialSessionWriteResult(String status, String errorCode) { this(status, errorCode, null); }
    public OfficialSessionWriteResult {
        if (!java.util.Set.of("ACCEPTED", "REJECTED", "UNKNOWN").contains(status)
                || (errorCode != null && !errorCode.matches("(?:OFFICIAL|PROXY)_[A-Z_]{1,60}"))) {
            throw new IllegalArgumentException("Invalid official write outcome");
        }
    }
}
