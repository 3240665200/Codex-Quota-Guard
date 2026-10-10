package com.pingche.web.quota;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** An authenticated member can inspect only their own accepted usage records. */
@Validated
@RestController
@RequestMapping("/api/v1/client/usage")
@PreAuthorize("hasAnyRole('USER', 'ADMIN')")
public class ClientUsageAuditController {
    private final UsageEventAuditService audit;
    private final UsageAnalyticsService analytics;

    public ClientUsageAuditController(UsageEventAuditService audit, UsageAnalyticsService analytics) {
        this.audit = audit;
        this.analytics = analytics;
    }

    @GetMapping("/analytics")
    public ResponseEntity<UsageAnalyticsService.PersonalResponse> analytics(Authentication authentication,
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(analytics.personalAnalytics((UUID) authentication.getPrincipal(), days));
    }

    @GetMapping("/events")
    public ResponseEntity<?> events(Authentication authentication,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue = "false") boolean includeMetadata,
            @RequestParam(defaultValue = "false") boolean includeTokenBreakdown) {
        UsageEventAuditPage pageData = audit.list((UUID) authentication.getPrincipal(), page, size);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(includeTokenBreakdown ? pageData
                        : includeMetadata ? MetadataPage.from(pageData) : LegacyPage.from(pageData));
    }

    // Older installed bridges reject unknown JSON fields. Their existing
    // endpoint keeps its exact shape; the admin ledger always includes metadata.
    public record LegacyItem(String id, java.time.Instant occurredAt, java.time.Instant receivedAt,
            long totalTokens, String source) { }
    public record MetadataItem(String id, java.time.Instant occurredAt, java.time.Instant receivedAt,
            long totalTokens, String source, String model, String reasoningEffort) { }
    public record MetadataPage(java.util.List<MetadataItem> items, int page, int size,
            long totalElements, int totalPages) {
        static MetadataPage from(UsageEventAuditPage value) {
            return new MetadataPage(value.items().stream().map(item -> new MetadataItem(item.id(), item.occurredAt(),
                    item.receivedAt(), item.totalTokens(), item.source(), item.model(), item.reasoningEffort()))
                    .toList(), value.page(), value.size(), value.totalElements(), value.totalPages());
        }
    }
    public record LegacyPage(java.util.List<LegacyItem> items, int page, int size, long totalElements, int totalPages) {
        static LegacyPage from(UsageEventAuditPage value) {
            return new LegacyPage(value.items().stream().map(item -> new LegacyItem(item.id(), item.occurredAt(),
                    item.receivedAt(), item.totalTokens(), item.source())).toList(), value.page(), value.size(),
                    value.totalElements(), value.totalPages());
        }
    }
}
