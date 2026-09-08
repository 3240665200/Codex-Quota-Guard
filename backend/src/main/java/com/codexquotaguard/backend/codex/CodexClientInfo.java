package com.codexquotaguard.backend.codex;

public record CodexClientInfo(String name, String title, String version) {

    public CodexClientInfo {
        if (isBlank(name) || isBlank(title) || isBlank(version)) {
            throw new IllegalArgumentException("Codex client metadata is required.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
