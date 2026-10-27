package com.pingche.web.quota;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OfficialQuotaSnapshotController {

    private final OfficialQuotaSnapshotService snapshots;
    private final ClientOfficialQuotaService clientQuota;

    public OfficialQuotaSnapshotController(OfficialQuotaSnapshotService snapshots, ClientOfficialQuotaService clientQuota) {
        this.snapshots = snapshots;
        this.clientQuota = clientQuota;
    }

    @GetMapping("/api/v1/client/official-quota")
    public ResponseEntity<ClientOfficialQuotaService.Response> current(Authentication authentication) {
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(clientQuota.read((UUID) authentication.getPrincipal()));
    }

    @PutMapping("/api/v1/client/official-quota")
    public OfficialQuotaSnapshotResponse report(Authentication authentication,
            @Valid @RequestBody OfficialQuotaSnapshotRequest request) {
        return snapshots.report((UUID) authentication.getPrincipal(), request);
    }

    @GetMapping("/api/v1/admin/users/{id}/official-quota")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<OfficialQuotaSnapshotResponse> read(@PathVariable UUID id) {
        OfficialQuotaSnapshotResponse response = snapshots.read(id);
        return response == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(response);
    }
}
