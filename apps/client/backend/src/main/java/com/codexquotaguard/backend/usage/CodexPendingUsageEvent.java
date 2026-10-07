package com.codexquotaguard.backend.usage;

import java.util.Objects;

/** A delayed legacy-snapshot event, held until a direct usage record has had time to arrive. */
public record CodexPendingUsageEvent(CodexUsageEvent event) {

    public CodexPendingUsageEvent {
        Objects.requireNonNull(event, "event is required.");
    }
}
