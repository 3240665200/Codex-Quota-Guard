package com.pingche.web.restriction;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/restrictions")
@PreAuthorize("hasRole('ADMIN')")
public class RestrictionManagementController {
    private final RestrictionManagementService service;
    private final ManagementTransportGuard transport;
    public RestrictionManagementController(RestrictionManagementService service, ManagementTransportGuard transport) { this.service = service; this.transport = transport; }
    @GetMapping public ResponseEntity<RestrictionManagementService.Response> read() { return result(service.read()); }
    @PutMapping public ResponseEntity<RestrictionManagementService.Response> configure(Authentication auth, HttpServletRequest http,
            @Valid @RequestBody RestrictionManagementService.Settings settings) {
        if (!transport.isAllowed(http)) throw new DomainException("RESTRICTION_TRANSPORT_REQUIRED", "当前传输方式不允许修改限制规则。", HttpStatus.BAD_REQUEST);
        return result(service.configure((UUID) auth.getPrincipal(), settings));
    }
    private <T> ResponseEntity<T> result(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
