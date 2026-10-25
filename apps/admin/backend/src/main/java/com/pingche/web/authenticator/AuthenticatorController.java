package com.pingche.web.authenticator;

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
@RequestMapping("/api/v1")
public class AuthenticatorController {
    private final AuthenticatorService service;
    private final ManagementTransportGuard transport;
    public AuthenticatorController(AuthenticatorService service, ManagementTransportGuard transport) { this.service = service; this.transport = transport; }
    @GetMapping("/admin/official-accounts/{id}/authenticator") @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuthenticatorService.Config> read(@PathVariable UUID id) { return noStore(service.read(id)); }
    @PutMapping("/admin/official-accounts/{id}/authenticator") @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<AuthenticatorService.Config> save(@PathVariable UUID id, @Valid @RequestBody AuthenticatorService.Request request, HttpServletRequest http) {
        requireTransport(http); return noStore(service.save(id, request));
    }
    @DeleteMapping("/admin/official-accounts/{id}/authenticator") @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) { service.delete(id); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
    @GetMapping("/client/login-assistance/authenticator")
    public ResponseEntity<AuthenticatorService.Availability> availability(Authentication auth) { return noStore(service.availability((UUID) auth.getPrincipal())); }
    @PostMapping("/client/login-assistance/authenticator/code")
    public ResponseEntity<AuthenticatorService.Code> code(Authentication auth, HttpServletRequest http) {
        requireTransport(http); return noStore(service.code((UUID) auth.getPrincipal()));
    }
    private void requireTransport(HttpServletRequest http) {
        if (!transport.isAllowed(http)) throw new DomainException("AUTHENTICATOR_HTTPS_REQUIRED", "当前传输方式不被服务器允许。", HttpStatus.BAD_REQUEST);
    }
    private <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache").body(body); }
}
