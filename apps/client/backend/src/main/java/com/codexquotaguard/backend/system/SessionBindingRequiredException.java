package com.codexquotaguard.backend.system;

/** Does not invalidate product login; only the protected workspace is closed. */
public final class SessionBindingRequiredException extends RuntimeException {
    public SessionBindingRequiredException() { super("SESSION_BINDING_REQUIRED", null, false, false); }
}
