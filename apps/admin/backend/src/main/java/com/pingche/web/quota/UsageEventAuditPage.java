package com.pingche.web.quota;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;

/** Safe, paged view of events already accepted by the central service. */
public record UsageEventAuditPage(List<Item> items, int page, int size,
        long totalElements, int totalPages) {

    public static UsageEventAuditPage from(Page<CodexUsageEvent> events) {
        return new UsageEventAuditPage(events.getContent().stream().map(Item::from).toList(),
                events.getNumber(), events.getSize(), events.getTotalElements(), events.getTotalPages());
    }

    public record Item(String id, Instant occurredAt, Instant receivedAt,
            long totalTokens, String source, String model, String reasoningEffort) {
        static Item from(CodexUsageEvent event) {
            return new Item(event.getId(), event.getOccurredAt(), event.getReceivedAt(),
                    event.getTotalTokens(), event.getSource(), event.getModel(), event.getReasoningEffort());
        }
    }
}
