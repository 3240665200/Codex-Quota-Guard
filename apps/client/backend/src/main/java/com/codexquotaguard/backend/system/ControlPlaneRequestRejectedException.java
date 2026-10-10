package com.codexquotaguard.backend.system;

/** Fixed local classification for a control-plane request rejected by validation. */
public final class ControlPlaneRequestRejectedException extends RuntimeException {

    public ControlPlaneRequestRejectedException() {
        super("CONTROL_PLANE_REQUEST_REJECTED", null, false, false);
    }
}
