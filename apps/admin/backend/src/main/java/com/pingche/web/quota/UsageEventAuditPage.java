package com.pingche.web.quota;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

/** Safe, paged view of events already accepted by the central service. */
public record UsageEventAuditPage(List<Item> items, int page, int size,
        long totalElements, int totalPages) {

    public static UsageEventAuditPage from(Page<CodexUsageEvent> events, Map<String, UsageModelPrice> prices) {
        return new UsageEventAuditPage(events.getContent().stream()
                .map(event -> Item.from(event, prices.get(event.getModel()))).toList(),
                events.getNumber(), events.getSize(), events.getTotalElements(), events.getTotalPages());
    }

    public record Item(String id, Instant occurredAt, Instant receivedAt,
            long totalTokens, String source, String model, String reasoningEffort,
            Long inputTokens, Long cachedInputTokens, Long cacheWriteInputTokens,
            Long outputTokens, Long reasoningOutputTokens, BigDecimal estimatedCostUsd, String costStatus) {
        static Item from(CodexUsageEvent event, UsageModelPrice price) {
            var estimate = UsagePricingCalculator.estimate(event, price);
            return new Item(event.getId(), event.getOccurredAt(), event.getReceivedAt(),
                    event.getTotalTokens(), event.getSource(), event.getModel(), event.getReasoningEffort(),
                    event.getInputTokens(), event.getCachedInputTokens(), event.getCacheWriteInputTokens(),
                    event.getOutputTokens(), event.getReasoningOutputTokens(),
                    estimate.estimatedCostUsd(), estimate.costStatus());
        }
    }
}
