package com.codexquotaguard.backend.codex;

import com.codexquotaguard.backend.controlplane.OfficialCodexRateLimits;
import com.codexquotaguard.backend.system.SystemSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Safe proxy for official account-level reference limits, separate from personal token quota. */
@RestController
@RequestMapping("/api/v1/codex")
public final class CodexRateLimitsController {

    private final SystemSessionService systemSessionService;
    private final LocalOfficialCodexRateLimitService localLimits;

    public CodexRateLimitsController(SystemSessionService systemSessionService,
            LocalOfficialCodexRateLimitService localLimits) {
        this.systemSessionService = systemSessionService;
        this.localLimits = localLimits;
    }

    @GetMapping("/rate-limits")
    public OfficialCodexRateLimits rateLimits() {
        systemSessionService.requireLocalUserId();
        OfficialCodexRateLimits snapshot = localLimits.read();
        // Revocation and network failures must reach the workspace gate immediately.
        systemSessionService.reportOfficialQuota(snapshot);
        return snapshot;
    }
}
