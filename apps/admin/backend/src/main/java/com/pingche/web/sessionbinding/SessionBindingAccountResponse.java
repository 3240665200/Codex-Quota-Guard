package com.pingche.web.sessionbinding;

import java.util.UUID;
import java.time.Instant;

public record SessionBindingAccountResponse(UUID officialAccountId, boolean quarantined, String reason,
        UUID activeAttemptId, String queueState, Instant retryAt) { }
