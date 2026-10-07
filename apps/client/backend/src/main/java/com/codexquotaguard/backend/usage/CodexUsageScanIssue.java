package com.codexquotaguard.backend.usage;

import java.util.Objects;

/** A content-free scanner diagnostic. It deliberately contains neither log text nor credentials. */
public record CodexUsageScanIssue(CodexUsageScanIssueCode code) {

    public CodexUsageScanIssue {
        Objects.requireNonNull(code, "code is required.");
    }
}
