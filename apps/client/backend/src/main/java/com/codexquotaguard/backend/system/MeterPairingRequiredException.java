package com.codexquotaguard.backend.system;

/** A configured Windows meter must be paired to the current system user. */
public final class MeterPairingRequiredException extends RuntimeException {
    public MeterPairingRequiredException() {
        super("Meter pairing is required", null, false, false);
    }
}
