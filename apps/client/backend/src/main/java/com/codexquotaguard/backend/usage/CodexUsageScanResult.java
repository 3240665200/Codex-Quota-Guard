package com.codexquotaguard.backend.usage;

import java.util.List;
import java.util.Objects;

/** Immutable result of a read-only local scan. */
public record CodexUsageScanResult(
        List<CodexUsageEvent> events,
        CodexUsageScanState nextState,
        List<CodexUsageScanIssue> issues
) {

    public CodexUsageScanResult {
        events = List.copyOf(Objects.requireNonNull(events, "events is required."));
        Objects.requireNonNull(nextState, "nextState is required.");
        issues = List.copyOf(Objects.requireNonNull(issues, "issues is required."));
    }
}
