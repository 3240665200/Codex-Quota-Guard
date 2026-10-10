package com.codexquotaguard.backend.controlplane;

import com.codexquotaguard.backend.usage.CodexExecutionContext;
import java.time.Instant;
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
            String model, String reasoningEffort) {
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
        }

        public Item(String id, String occurredAt, String receivedAt, long totalTokens, String source) {
            this(id, occurredAt, receivedAt, totalTokens, source, null, null);
        }
    }
}
