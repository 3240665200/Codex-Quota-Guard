package com.pingche.web.quota;

import java.time.Instant;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

/** Safe, paged view of events already accepted by the central service. */
public record UsageEventAuditPage(List<Item> items, int page, int size,
        long totalElements, int totalPages) {

    public static UsageEventAuditPage from(Page<CodexUsageEvent> events, Map<String, UsageBillingService.Entry> entries) {
        return new UsageEventAuditPage(events.getContent().stream()
                .map(event -> Item.from(event, entries.get(event.getId()))).toList(),
                events.getNumber(), events.getSize(), events.getTotalElements(), events.getTotalPages());
    }

    public record Item(String id, Instant occurredAt, Instant receivedAt,
            long totalTokens, String source, String model, String reasoningEffort,
            Long inputTokens, Long cachedInputTokens, Long cacheWriteInputTokens,
            Long outputTokens, Long reasoningOutputTokens, BigDecimal estimatedCostUsd, String costStatus,
            String serviceTier, BigDecimal billingMultiplier, String billingModeStatus, String billingOrigin, String billingModeSource) {
        static Item from(CodexUsageEvent event, UsageBillingService.Entry entry) {
            return new Item(event.getId(), event.getOccurredAt(), event.getReceivedAt(),
                    event.getTotalTokens(), event.getSource(), event.getModel(), event.getReasoningEffort(),
                    event.getInputTokens(), event.getCachedInputTokens(), event.getCacheWriteInputTokens(),
                    event.getOutputTokens(), event.getReasoningOutputTokens(),
                    entry == null ? null : entry.costUsd(), entry == null ? "PENDING_BILLING" : entry.costStatus(),
                    event.getServiceTier(), entry == null ? null : entry.multiplier(),
                    entry == null ? null : entry.modeStatus(), entry == null ? null : entry.origin(), entry == null ? null : entry.modeSource());
        }
    }
}
