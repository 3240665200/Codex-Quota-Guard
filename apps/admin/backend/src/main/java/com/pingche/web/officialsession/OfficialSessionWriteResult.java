package com.pingche.web.officialsession;

/** HTTP acknowledgement only, never a claim that every official session has disappeared. */
public record OfficialSessionWriteResult(String status, String errorCode) {
    public OfficialSessionWriteResult {
        if (!java.util.Set.of("ACCEPTED", "REJECTED", "UNKNOWN").contains(status)
                || (errorCode != null && !errorCode.matches("OFFICIAL_[A-Z_]{1,60}"))) {
            throw new IllegalArgumentException("Invalid official write outcome");
        }
    }
}
