package com.pingche.web.quota;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public record CodexUsageReportRequest(
        @NotNull @Size(min = 1, max = 100) List<@Valid Event> events
) {
    public record Event(
            @NotNull @Size(max = 100)
            @Pattern(regexp = "codex-local-(usage|cumulative):v1:[0-9a-f]{64}") String eventId,
            @NotNull Instant occurredAt,
            @Min(0) @Max(100_000_000) long totalTokens,
            @NotNull Source source
    ) { }

    public enum Source {
        TOKEN_USAGE_RECORD,
        CUMULATIVE_TOKEN_COUNT_FALLBACK
    }
}
