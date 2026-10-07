package com.pingche.web.meter;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MeterEnrollmentRequest(@NotNull UUID deviceId) { }
