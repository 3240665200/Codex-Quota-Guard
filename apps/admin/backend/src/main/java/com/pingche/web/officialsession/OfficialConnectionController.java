package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/official-accounts/{id}")
@PreAuthorize("hasRole('ADMIN')")
public class OfficialConnectionController {
    private final OfficialConnectionService service;
    private final ManagementTransportGuard transportGuard;
    public OfficialConnectionController(OfficialConnectionService service, ManagementTransportGuard transportGuard) {
        this.service = service;
        this.transportGuard = transportGuard;
    }

    @GetMapping("/connection")
    public ResponseEntity<OfficialConnectionResponse> read(@PathVariable UUID id, HttpServletRequest http) {
        requireAllowedTransport(http);
        return noStore(service.read(id));
    }

    @PutMapping("/connection")
    public ResponseEntity<OfficialConnectionResponse> save(@PathVariable UUID id,
            @Valid @RequestBody OfficialConnectionRequest request, HttpServletRequest http) {
        requireAllowedTransport(http);
        return noStore(service.save(id, request));
    }

    @DeleteMapping("/connection")
    public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest http) {
        requireAllowedTransport(http);
        service.delete(id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PutMapping("/connection/proxy")
    public ResponseEntity<OfficialConnectionResponse> updateProxy(@PathVariable UUID id,
            @Valid @RequestBody OfficialConnectionProxyRequest request, HttpServletRequest http) {
        requireAllowedTransport(http);
        return noStore(service.updateProxy(id, request));
    }

    @PostMapping("/connection/refresh")
    public ResponseEntity<OfficialConnectionRefreshResponse> refresh(@PathVariable UUID id, HttpServletRequest http) {
        requireAllowedTransport(http);
        return noStore(service.refresh(id));
    }

    @GetMapping("/sessions")
    public ResponseEntity<OfficialConnectionRefreshResponse> sessions(@PathVariable UUID id, HttpServletRequest http) {
        requireAllowedTransport(http);
        return noStore(service.sessions(id));
    }

    private void requireAllowedTransport(HttpServletRequest request) {
        if (!transportGuard.isAllowed(request)) throw new DomainException("OFFICIAL_HTTPS_REQUIRED", "Request transport is not permitted by the server configuration", HttpStatus.BAD_REQUEST);
    }
    private <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
