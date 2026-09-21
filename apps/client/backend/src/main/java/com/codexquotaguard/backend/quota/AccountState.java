package com.codexquotaguard.backend.quota;

public record AccountState(boolean authenticated, String planType) {

    public AccountState {
        planType = planType == null ? "" : planType.trim();
    }
}
