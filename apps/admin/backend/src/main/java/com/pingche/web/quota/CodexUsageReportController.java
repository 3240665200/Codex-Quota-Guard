package com.pingche.web.quota;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/client/usage")
public class CodexUsageReportController {

    private final CodexUsageReportService service;

    public CodexUsageReportController(CodexUsageReportService service) {
        this.service = service;
    }

    @PostMapping("/events")
    public ResponseEntity<QuotaOverviewResponse> report(
            Authentication authentication,
            @Valid @RequestBody CodexUsageReportRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.report((UUID) authentication.getPrincipal(), request));
    }
}
