package com.pingche.web.meter;

import com.pingche.web.quota.CodexUsageReportRequest;
import com.pingche.web.quota.CodexUsageReportService;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/meter")
@PreAuthorize("hasRole('METER')")
public class MeterController {
    private final MeterDeviceService devices;
    private final CodexUsageReportService usage;

    public MeterController(MeterDeviceService devices, CodexUsageReportService usage) {
        this.devices = devices;
        this.usage = usage;
    }

    @PostMapping("/heartbeat")
    public ResponseEntity<Map<String, Instant>> heartbeat(Authentication authentication) {
        MeterIdentity device = (MeterIdentity) authentication.getPrincipal();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("seenAt", devices.heartbeat(device.deviceId())));
    }

    @PostMapping("/usage/events")
    public ResponseEntity<Map<String, Integer>> report(Authentication authentication,
            @Valid @RequestBody CodexUsageReportRequest request) {
        MeterIdentity device = (MeterIdentity) authentication.getPrincipal();
        usage.report(device.userId(), request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("acceptedEvents", request.events().size()));
    }

    @PostMapping("/credential/renew")
    public ResponseEntity<MeterRenewalResponse> renew(Authentication authentication) {
        MeterIdentity device = (MeterIdentity) authentication.getPrincipal();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(devices.renew(device.deviceId()));
    }
}
