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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class OfficialAuthorizationPresetController {
    private final OfficialAuthorizationPresetService service;
    private final ManagementTransportGuard transportGuard;

    public OfficialAuthorizationPresetController(OfficialAuthorizationPresetService service, ManagementTransportGuard transportGuard) {
        this.service = service;
        this.transportGuard = transportGuard;
    }

    @GetMapping("/official-authorization-preset")
    public ResponseEntity<OfficialAuthorizationPresetResponse> metadata(HttpServletRequest http) {
        requireAllowedTransport(http);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.metadata());
    }

    @PostMapping("/official-accounts/{id}/connection/preset")
    public ResponseEntity<OfficialConnectionResponse> apply(@PathVariable UUID id,
            @Valid @RequestBody OfficialAuthorizationPresetRequest request, HttpServletRequest http) {
        requireAllowedTransport(http);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.apply(id, request));
    }

    private void requireAllowedTransport(HttpServletRequest request) {
        if (!transportGuard.isAllowed(request)) throw new DomainException("OFFICIAL_HTTPS_REQUIRED",
                "Request transport is not permitted by the server configuration", HttpStatus.BAD_REQUEST);
    }
}
