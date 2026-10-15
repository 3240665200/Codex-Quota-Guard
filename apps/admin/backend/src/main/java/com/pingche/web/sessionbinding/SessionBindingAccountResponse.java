package com.pingche.web.sessionbinding;

import java.util.UUID;

public record SessionBindingAccountResponse(UUID officialAccountId, boolean quarantined, String reason,
        UUID activeAttemptId) { }
