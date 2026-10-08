package com.pingche.web.quota;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/teams")
@PreAuthorize("hasRole('ADMIN')")
public class AdminTeamQuotaController {

    private final QuotaOverviewService quotas;
    private final QuotaResetService resets;

    public AdminTeamQuotaController(QuotaOverviewService quotas, QuotaResetService resets) {
        this.quotas = quotas;
        this.resets = resets;
    }

    @GetMapping("/{id}/quota-overview")
    public ResponseEntity<TeamQuotaOverviewResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(quotas.teamOverview(id));
    }

    @PostMapping("/{id}/quota-reset")
    public ResponseEntity<Void> reset(@PathVariable UUID id) {
        resets.resetTeam(id);
        return ResponseEntity.noContent().build();
    }
}
