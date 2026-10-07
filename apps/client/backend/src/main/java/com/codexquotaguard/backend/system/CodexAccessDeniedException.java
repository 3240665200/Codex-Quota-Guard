package com.codexquotaguard.backend.system;

/** The central service rejected a local usage report for this system user. */
public final class CodexAccessDeniedException extends RuntimeException {
    public CodexAccessDeniedException() {
        super("USAGE_REPORT_DENIED", null, false, false);
    }
}
