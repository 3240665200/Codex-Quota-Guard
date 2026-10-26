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
            @NotNull Source source,
            @Size(max = 96) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}") String model,
            @Pattern(regexp = "none|minimal|low|medium|high|xhigh|max|ultra") String reasoningEffort,
            @Min(0) @Max(100_000_000) Long inputTokens,
            @Min(0) @Max(100_000_000) Long cachedInputTokens,
            @Min(0) @Max(100_000_000) Long cacheWriteInputTokens,
            @Min(0) @Max(100_000_000) Long outputTokens,
            @Min(0) @Max(100_000_000) Long reasoningOutputTokens,
            @Pattern(regexp = "default|standard|fast|priority|flex|auto|scale|unknown") String serviceTier
    ) {
        public Event(String eventId, Instant occurredAt, long totalTokens, Source source,
                String model, String reasoningEffort, Long inputTokens, Long cachedInputTokens,
                Long cacheWriteInputTokens, Long outputTokens, Long reasoningOutputTokens) {
            this(eventId, occurredAt, totalTokens, source, model, reasoningEffort, inputTokens, cachedInputTokens,
                    cacheWriteInputTokens, outputTokens, reasoningOutputTokens, null);
        }
        public Event(String eventId, Instant occurredAt, long totalTokens, Source source) {
            this(eventId, occurredAt, totalTokens, source, null, null);
        }

        public Event(String eventId, Instant occurredAt, long totalTokens, Source source,
                String model, String reasoningEffort) {
            this(eventId, occurredAt, totalTokens, source, model, reasoningEffort,
                    null, null, null, null, null);
        }
    }

    public enum Source {
        TOKEN_USAGE_RECORD,
        CUMULATIVE_TOKEN_COUNT_FALLBACK
    }
}
