package com.codexquotaguard.backend.controlplane;

/** A user-safe classification of a control-plane request failure. */
public final class ControlPlaneException extends RuntimeException {

    public enum Reason {
        UNAUTHORIZED,
        INVALID_REQUEST,
        UNAVAILABLE
    }

    private final Reason reason;

    private ControlPlaneException(Reason reason) {
        super(reason.name(), null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public static ControlPlaneException unauthorized() {
        return new ControlPlaneException(Reason.UNAUTHORIZED);
    }

    public static ControlPlaneException invalidRequest() {
        return new ControlPlaneException(Reason.INVALID_REQUEST);
    }

    public static ControlPlaneException unavailable() {
        return new ControlPlaneException(Reason.UNAVAILABLE);
    }
}
