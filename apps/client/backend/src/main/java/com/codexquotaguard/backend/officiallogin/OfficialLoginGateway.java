package com.codexquotaguard.backend.officiallogin;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface OfficialLoginGateway {
    List<Job> jobs(String token);
    Job get(String token, UUID id);
    Job claim(String token, UUID id, UUID helperId);
    Job complete(String token, UUID id, Completion body);
    Job cancel(String token, UUID id);
    record Job(UUID id, UUID officialAccountId, String accountName, String state, Instant expiresAt,
            String errorCode, UUID proxyNodeId) { }
    record Completion(UUID helperId, String authorization, String cookie, String requestedAccountId) {
        @Override public String toString() { return "OfficialBrowserCompletion[redacted]"; }
    }
}
