package com.pingche.web.meter;

import java.time.Instant;
import java.util.UUID;

public record MeterRenewalResponse(UUID deviceId, UUID userId, Instant expiresAt) { }
