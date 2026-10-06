package com.codexquotaguard.backend.quota;

import com.codexquotaguard.backend.controlplane.PersonalQuotaOverview;
import com.codexquotaguard.backend.system.SystemSessionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Fixed loopback proxy for the signed-in product user's quota overview. */
@RestController
@RequestMapping("/api/v1/quota")
public final class QuotaController {

    private final SystemSessionService sessionService;

    public QuotaController(SystemSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @GetMapping("/overview")
    public PersonalQuotaOverview overview() {
        return sessionService.quotaOverview();
    }
}
