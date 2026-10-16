package com.codexquotaguard.backend.codex;

/** A narrow signal; the native layer must independently validate takeover before acting. */
public final class CodexCcSwitchSessionConflictException extends RuntimeException {
    public CodexCcSwitchSessionConflictException() {
        super("Codex official session is replaced by a managed proxy credential");
    }
}
