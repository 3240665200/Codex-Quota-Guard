package com.pingche.web.codex;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record CodexAccountUpdateRequest(
        @Email @Size(max = 320) String email,
        @Size(max = 120) String displayName,
        CodexAccount.Status status,
        JsonNode credential
) {
    public CodexAccountUpdateRequest {
        email = email == null ? null : email.trim();
        displayName = displayName == null ? null : displayName.trim();
    }

    @Override
    public String toString() { return "CodexAccountUpdateRequest[redacted]"; }
}
