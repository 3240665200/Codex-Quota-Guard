package com.pingche.web.sessionbinding;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/session-bindings")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSessionBindingController {
    private final SessionBindingService service;
    private final ManagementTransportGuard transport;
    public AdminSessionBindingController(SessionBindingService service, ManagementTransportGuard transport) { this.service = service; this.transport = transport; }

    @GetMapping
    public ResponseEntity<List<SessionBindingResponse>> list(@RequestParam(required=false) UUID officialAccountId, HttpServletRequest http) {
        requireTransport(http); return response(service.bindings(officialAccountId));
    }
    @GetMapping("/accounts/{id}")
    public ResponseEntity<SessionBindingAccountResponse> state(@PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); return response(service.accountState(id));
    }
    @PostMapping("/accounts/{id}/recover")
    public ResponseEntity<SessionBindingAccountResponse> recover(@PathVariable UUID id, @RequestBody RecoveryRequest body, HttpServletRequest http) {
        requireTransport(http); return response(service.recover(id, Boolean.TRUE.equals(body.acknowledgeLateLoginRisk())));
    }
    private void requireTransport(HttpServletRequest request) { if (!transport.isAllowed(request)) throw new DomainException("SESSION_BINDING_HTTPS_REQUIRED", "HTTPS is required for device session binding", HttpStatus.BAD_REQUEST); }
    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    public record RecoveryRequest(Boolean acknowledgeLateLoginRisk) { }
}
