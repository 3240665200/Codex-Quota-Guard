package com.codexquotaguard.backend.controlplane;

/** Safe team metadata associated with the current user's managed account. */
public record QuotaTeam(String id, String name) {

    public QuotaTeam {
        if (isBlank(id) || isBlank(name)) {
            throw new IllegalArgumentException("A complete quota team is required.");
        }
        id = id.trim();
        name = name.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
