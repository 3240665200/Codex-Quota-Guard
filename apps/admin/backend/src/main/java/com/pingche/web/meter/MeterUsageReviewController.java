package com.pingche.web.meter;

import com.pingche.web.quota.CodexUsageReportRequest;
import com.pingche.web.quota.UsageReviewService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/meter/usage/reviews")
@PreAuthorize("hasRole('METER')")
public class MeterUsageReviewController {
    private final UsageReviewService reviews;
    public MeterUsageReviewController(UsageReviewService reviews) { this.reviews = reviews; }
    @PostMapping
    public ResponseEntity<Map<String, List<String>>> receive(Authentication authentication, @Valid @RequestBody CodexUsageReportRequest request) {
        MeterIdentity device = (MeterIdentity) authentication.getPrincipal();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(
                Map.of("quarantinedEventIds", reviews.receive(device.userId(), device.deviceId(), request)));
    }
}
