package com.codexquotaguard.backend.usage;

import java.time.Instant;
import java.util.Objects;

/**
 * A locally measured Codex token event.
 *
 * <p>The values are token counts, not official account-quota percentages. In particular, callers must not
 * reinterpret {@link #totalTokens()} as a five-hour or weekly quota percentage.</p>
 *
 * <p>{@link #cachedInputTokens()} is reported as a component of {@link #inputTokens()}, and
 * {@link #reasoningOutputTokens()} can be a component of {@link #outputTokens()}; consumers must not add
 * those component fields a second time when computing a total.</p>
 */
public record CodexUsageEvent(
        String eventId,
        Instant occurredAt,
        long inputTokens,
        long cachedInputTokens,
        long cacheWriteInputTokens,
        long outputTokens,
        long reasoningOutputTokens,
        long totalTokens,
        CodexUsageEventSource source
) {

    public CodexUsageEvent {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId is required.");
        }
        Objects.requireNonNull(occurredAt, "occurredAt is required.");
        Objects.requireNonNull(source, "source is required.");
        requireNonNegative(inputTokens, "inputTokens");
        requireNonNegative(cachedInputTokens, "cachedInputTokens");
        requireNonNegative(cacheWriteInputTokens, "cacheWriteInputTokens");
        requireNonNegative(outputTokens, "outputTokens");
        requireNonNegative(reasoningOutputTokens, "reasoningOutputTokens");
        requireNonNegative(totalTokens, "totalTokens");
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " must not be negative.");
        }
    }
}
