package com.codexquotaguard.backend.usage;

/** Identifies whether a local token measurement was directly recorded or reconstructed from an older log format. */
public enum CodexUsageEventSource {
    /** A per-response {@code token_usage_record}; the preferred measured source in current Codex rollouts. */
    TOKEN_USAGE_RECORD,
    /** A delayed, conservative delta derived from a legacy cumulative {@code token_count} snapshot. */
    CUMULATIVE_TOKEN_COUNT_FALLBACK
}
