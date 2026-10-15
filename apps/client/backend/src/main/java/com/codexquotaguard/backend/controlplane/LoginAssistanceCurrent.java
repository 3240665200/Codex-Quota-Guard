package com.codexquotaguard.backend.controlplane;

/** Server-owned listener restoration; neither this wrapper nor its code is persisted locally. */
public record LoginAssistanceCurrent(LoginAssistanceListener listener) {
    @Override public String toString() { return "LoginAssistanceCurrent[present="+(listener!=null)+"]"; }
}
