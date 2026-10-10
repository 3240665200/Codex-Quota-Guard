package com.codexquotaguard.backend.system;

/** Raised when the desktop has no valid in-memory product-user session. */
public final class SystemSessionRequiredException extends RuntimeException {

    SystemSessionRequiredException() {
        super("SYSTEM_SESSION_REQUIRED", null, false, false);
    }
}
