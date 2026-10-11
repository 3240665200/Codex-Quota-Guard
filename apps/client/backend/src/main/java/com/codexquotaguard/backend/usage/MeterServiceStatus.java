package com.codexquotaguard.backend.usage;

/** Public, credential-free status written by the Rust OS service. */
public record MeterServiceStatus(
        String state,
        Long lastScanEpochSeconds,
        Long lastReportEpochSeconds,
        int reportedEvents,
        String errorCode,
        String version
) {
    public static MeterServiceStatus notInstalled() {
        return new MeterServiceStatus("NOT_INSTALLED", null, null, 0, null, null);
    }

    public static MeterServiceStatus offline() {
        return new MeterServiceStatus("OFFLINE", null, null, 0, null, null);
    }
}
