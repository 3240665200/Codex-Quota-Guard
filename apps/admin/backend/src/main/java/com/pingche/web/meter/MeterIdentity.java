package com.pingche.web.meter;

import java.util.UUID;

public record MeterIdentity(UUID deviceId, UUID userId) { }
