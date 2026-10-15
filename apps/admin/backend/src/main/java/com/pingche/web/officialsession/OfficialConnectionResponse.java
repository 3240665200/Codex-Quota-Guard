package com.pingche.web.officialsession;

import java.time.Instant;
import java.util.UUID;

public record OfficialConnectionResponse(boolean configured, boolean encryptionReady, String status,
        String requestedAccountId, UUID proxyNodeId, boolean hasAuthorization, boolean hasCookie,
        Instant importedAt, Instant lastAttemptAt, Instant lastSuccessAt, String lastErrorCode, long version) { }
