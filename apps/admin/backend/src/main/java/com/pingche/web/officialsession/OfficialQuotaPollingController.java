package com.pingche.web.officialsession;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/official-accounts/{id}/quota-polling")
@PreAuthorize("hasRole('ADMIN')")
public class OfficialQuotaPollingController {
    private final OfficialQuotaPollingService service;
    public OfficialQuotaPollingController(OfficialQuotaPollingService service) { this.service = service; }
    @GetMapping public ResponseEntity<OfficialQuotaPollingService.Response> read(@PathVariable UUID id) { return noStore(service.read(id)); }
    @PutMapping public ResponseEntity<OfficialQuotaPollingService.Response> save(@PathVariable UUID id,
            @Valid @RequestBody OfficialQuotaPollingService.Settings settings) { return noStore(service.configure(id, settings)); }
    @PostMapping("/refresh") public ResponseEntity<OfficialQuotaPollingService.Response> refresh(@PathVariable UUID id) { return noStore(service.refresh(id)); }
    private <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
