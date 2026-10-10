package com.codexquotaguard.backend.system;

/** Raised without retaining remote error details, headers, or response bodies. */
public final class ControlPlaneUnavailableException extends RuntimeException {

    ControlPlaneUnavailableException() {
        super("CONTROL_PLANE_UNAVAILABLE", null, false, false);
    }
}
