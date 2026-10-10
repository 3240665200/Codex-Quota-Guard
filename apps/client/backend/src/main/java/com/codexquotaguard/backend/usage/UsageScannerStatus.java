package com.codexquotaguard.backend.usage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Safe scanner progress without log contents, paths, or credentials. */
public record UsageScannerStatus(
        String state,
        Instant lastScannedAt,
        Instant lastReportedAt,
        int lastReportedEvents,
        List<String> issues,
        String errorCode,
        Map<String, Integer> issueCounts
) {
    public UsageScannerStatus {
        issues = List.copyOf(issues);
        issueCounts = Map.copyOf(new LinkedHashMap<>(issueCounts == null ? Map.of() : issueCounts));
    }

    public UsageScannerStatus(String state, Instant lastScannedAt, Instant lastReportedAt,
            int lastReportedEvents, List<String> issues, String errorCode) {
        this(state, lastScannedAt, lastReportedAt, lastReportedEvents, issues, errorCode, Map.of());
    }

    public UsageScannerStatus(String state, Instant lastScannedAt, Instant lastReportedAt,
            int lastReportedEvents, List<String> issues) {
        this(state, lastScannedAt, lastReportedAt, lastReportedEvents, issues, null, Map.of());
    }

    public static UsageScannerStatus signedOut() {
        return new UsageScannerStatus("SIGNED_OUT", null, null, 0, List.of());
    }
}
