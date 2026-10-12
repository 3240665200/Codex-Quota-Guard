package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.controlplane.ControlPlaneException;

public final class LoginAssistanceException extends RuntimeException {
    private final ControlPlaneException.Reason reason;
    public LoginAssistanceException(ControlPlaneException.Reason reason) {
        super("LOGIN_ASSISTANCE_REJECTED", null, false, false);
        this.reason = reason;
    }
    public ControlPlaneException.Reason reason() { return reason; }
}
