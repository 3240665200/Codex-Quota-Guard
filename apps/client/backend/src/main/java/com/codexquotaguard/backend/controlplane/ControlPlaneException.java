package com.codexquotaguard.backend.controlplane;

/** A user-safe classification of a control-plane request failure. */
public final class ControlPlaneException extends RuntimeException {

    public enum Reason {
        UNAUTHORIZED,
        ACCESS_DENIED,
        BINDING_REQUIRED,
        INVALID_REQUEST,
        UNAVAILABLE,
        MAIL_BUSY,
        MAIL_RATE_LIMITED,
        MAIL_REJECTED
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

    public static ControlPlaneException accessDenied() {
        return new ControlPlaneException(Reason.ACCESS_DENIED);
    }

    public static ControlPlaneException unavailable() {
        return new ControlPlaneException(Reason.UNAVAILABLE);
    }

    public static ControlPlaneException bindingRequired() { return new ControlPlaneException(Reason.BINDING_REQUIRED); }

    public static ControlPlaneException mailBusy() { return new ControlPlaneException(Reason.MAIL_BUSY); }
    public static ControlPlaneException mailRateLimited() { return new ControlPlaneException(Reason.MAIL_RATE_LIMITED); }
    public static ControlPlaneException mailRejected() { return new ControlPlaneException(Reason.MAIL_REJECTED); }
}
