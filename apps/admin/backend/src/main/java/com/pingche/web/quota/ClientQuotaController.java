package com.pingche.web.quota;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/client/quota")
public class ClientQuotaController {

    private final QuotaOverviewService quotaOverview;

    public ClientQuotaController(QuotaOverviewService quotaOverview) {
        this.quotaOverview = quotaOverview;
    }

    @GetMapping("/overview")
    public QuotaOverviewResponse overview(Authentication authentication,
            @RequestParam(defaultValue = "false") boolean includeTeamMembers) {
        UUID userId = (UUID) authentication.getPrincipal();
        return includeTeamMembers ? quotaOverview.overview(userId, true) : quotaOverview.overview(userId);
    }
}
