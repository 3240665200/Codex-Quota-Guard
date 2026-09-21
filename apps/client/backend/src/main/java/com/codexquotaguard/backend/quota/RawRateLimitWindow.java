package com.codexquotaguard.backend.quota;

/** Raw window fields returned by Codex app-server. */
public record RawRateLimitWindow(Double usedPercent, Long windowDurationMins, Long resetsAt) {
}
