package com.codexquotaguard.backend.usage;

/** Non-sensitive outcomes that let a caller retry an incomplete read without logging rollout contents. */
public enum CodexUsageScanIssueCode {
    SESSIONS_DIRECTORY_UNAVAILABLE,
    FILE_UNREADABLE,
    MALFORMED_JSONL_RECORD,
    OVERSIZED_JSONL_RECORD,
    INVALID_USAGE_RECORD,
    CUMULATIVE_COUNTER_RESET
}
