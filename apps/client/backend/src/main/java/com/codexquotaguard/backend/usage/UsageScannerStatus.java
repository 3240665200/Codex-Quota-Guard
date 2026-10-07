package com.codexquotaguard.backend.usage;

import java.time.Instant;
import java.util.List;

/** Safe scanner progress without log contents, paths, or credentials. */
public record UsageScannerStatus(
        String state,
        Instant lastScannedAt,
        Instant lastReportedAt,
        int lastReportedEvents,
        List<String> issues
) {
    public UsageScannerStatus {
        issues = List.copyOf(issues);
    }

    public static UsageScannerStatus signedOut() {
        return new UsageScannerStatus("SIGNED_OUT", null, null, 0, List.of());
    }
}
