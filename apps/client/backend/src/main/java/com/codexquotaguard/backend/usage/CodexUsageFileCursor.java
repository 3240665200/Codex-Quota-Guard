package com.codexquotaguard.backend.usage;

import java.util.List;
import java.util.Objects;

/**
 * Durable, content-free progress for one JSONL file relative to the configured {@code sessions} directory.
 *
 * <p>The digests let the scanner recognize replacement/rotation without retaining any rollout contents.</p>
 */
public record CodexUsageFileCursor(
        String fileIdentityDigest,
        int prefixLength,
        String prefixDigest,
        long nextByteOffset,
        boolean directUsageRecordObserved,
        CodexCumulativeUsage cumulativeUsage,
        List<CodexPendingUsageEvent> pendingFallbackEvents,
        CodexExecutionContext currentContext,
        java.util.Map<String, CodexExecutionContext> turnContexts
) {

    public CodexUsageFileCursor {
        requireText(fileIdentityDigest, "fileIdentityDigest");
        if (prefixLength < 0) {
            throw new IllegalArgumentException("prefixLength must not be negative.");
        }
        requireText(prefixDigest, "prefixDigest");
        if (nextByteOffset < 0) {
            throw new IllegalArgumentException("nextByteOffset must not be negative.");
        }
        pendingFallbackEvents = List.copyOf(Objects.requireNonNull(pendingFallbackEvents, "pendingFallbackEvents is required."));
        turnContexts = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(turnContexts == null ? java.util.Map.of() : turnContexts));
    }

    public CodexUsageFileCursor(String fileIdentityDigest, int prefixLength, String prefixDigest, long nextByteOffset,
            boolean directUsageRecordObserved, CodexCumulativeUsage cumulativeUsage,
            List<CodexPendingUsageEvent> pendingFallbackEvents) {
        this(fileIdentityDigest, prefixLength, prefixDigest, nextByteOffset, directUsageRecordObserved,
                cumulativeUsage, pendingFallbackEvents, null, java.util.Map.of());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
    }
}
