package com.pingche.web.quota;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/usage")
@PreAuthorize("hasRole('ADMIN')")
public class UsageAnalyticsController {
    private final UsageAnalyticsService analytics;

    public UsageAnalyticsController(UsageAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/analytics")
    public ResponseEntity<UsageAnalyticsService.Response> analytics(
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "DAY") UsageAnalyticsService.Granularity granularity,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}") String model,
            @RequestParam(required = false) @Pattern(regexp = "none|minimal|low|medium|high|xhigh|max|ultra") String reasoningEffort,
            @RequestParam(required = false) UUID userId, @RequestParam(required = false) UUID teamId,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(analytics.analytics(
                new UsageAnalyticsService.Filter(from, to, granularity, model, reasoningEffort, userId, teamId, page, size)));
    }

    @GetMapping("/options")
    public ResponseEntity<UsageAnalyticsService.Options> options() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(analytics.options());
    }
}
