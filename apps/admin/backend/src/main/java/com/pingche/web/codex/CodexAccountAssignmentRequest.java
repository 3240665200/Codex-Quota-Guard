package com.pingche.web.codex;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CodexAccountAssignmentRequest(@NotNull UUID codexAccountId) {
}
