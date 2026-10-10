package com.codexquotaguard.backend.controlplane;

import com.codexquotaguard.backend.usage.CodexExecutionContext;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/** Safe projection of accepted billing records; never contains raw session data. */
public record UserUsageEventPage(List<Item> items, int page, int size,
        long totalElements, int totalPages) {
    public UserUsageEventPage {
        if (items == null || page < 0 || size < 1 || size > 100 || items.size() > size
                || totalElements < 0 || totalPages < 0) {
            throw new IllegalArgumentException("Invalid usage ledger page.");
        }
        items = List.copyOf(items);
    }

    public record Item(String id, String occurredAt, String receivedAt, long totalTokens, String source,
            String model, String reasoningEffort, Long inputTokens, Long cachedInputTokens,
            Long cacheWriteInputTokens, Long outputTokens, Long reasoningOutputTokens,
            BigDecimal estimatedCostUsd, String costStatus) {
        public Item {
            if (id == null || !id.matches("[a-zA-Z0-9-]{1,128}") || totalTokens < 0
                    || !Set.of("TOKEN_USAGE_RECORD", "CUMULATIVE_TOKEN_COUNT_FALLBACK").contains(source)) {
                throw new IllegalArgumentException("Invalid usage ledger item.");
            }
            Instant.parse(occurredAt);
            Instant.parse(receivedAt);
            CodexExecutionContext metadata = new CodexExecutionContext(model, reasoningEffort);
            model = metadata.model();
            reasoningEffort = metadata.reasoningEffort();
            for (Long tokens : new Long[] {inputTokens, cachedInputTokens, cacheWriteInputTokens,
                    outputTokens, reasoningOutputTokens}) {
                if (tokens != null && tokens < 0) throw new IllegalArgumentException("Invalid token breakdown.");
            }
            if (estimatedCostUsd != null && estimatedCostUsd.signum() < 0) {
                throw new IllegalArgumentException("Invalid estimated USD cost.");
            }
            if (costStatus != null && !Set.of("PRICED", "MISSING_PRICE", "MISSING_BREAKDOWN",
                    "INVALID_BREAKDOWN").contains(costStatus)) {
                throw new IllegalArgumentException("Invalid cost status.");
            }
        }

        public Item(String id, String occurredAt, String receivedAt, long totalTokens, String source) {
            this(id, occurredAt, receivedAt, totalTokens, source, null, null);
        }

        public Item(String id, String occurredAt, String receivedAt, long totalTokens, String source,
                String model, String reasoningEffort) {
            this(id, occurredAt, receivedAt, totalTokens, source, model, reasoningEffort,
                    null, null, null, null, null, null, null);
        }
    }
}
