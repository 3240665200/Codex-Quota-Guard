package com.pingche.web.sessionbinding;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class ClientAccessController {
    private final ClientAccessService access;

    public ClientAccessController(ClientAccessService access) { this.access = access; }

    @GetMapping("/api/v1/client/access")
    public ResponseEntity<ClientAccessResponse> access(Authentication authentication,
            @RequestHeader(value = ClientAccessService.DEVICE_HEADER, required = false) String deviceId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(access.access((UUID) authentication.getPrincipal(), deviceId));
    }
}
