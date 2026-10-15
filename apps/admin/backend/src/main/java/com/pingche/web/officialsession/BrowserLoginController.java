package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class BrowserLoginController {
    private final BrowserLoginService service;
    private final ManagementTransportGuard transport;
    public BrowserLoginController(BrowserLoginService service, ManagementTransportGuard transport) { this.service=service;this.transport=transport; }
    @PostMapping("/official-accounts/{accountId}/browser-login")
    public ResponseEntity<BrowserLoginService.Job> create(@PathVariable UUID accountId,@Valid @RequestBody BrowserLoginService.StartRequest body,
            Authentication auth,HttpServletRequest http) { check(http);return result(service.create(user(auth),accountId,body)); }
    @GetMapping("/browser-logins")
    public ResponseEntity<List<BrowserLoginService.Job>> list(Authentication auth,HttpServletRequest http) { check(http);return result(service.list(user(auth))); }
    @GetMapping("/browser-logins/{id}")
    public ResponseEntity<BrowserLoginService.Job> get(@PathVariable UUID id,Authentication auth,HttpServletRequest http) { check(http);return result(service.get(user(auth),id)); }
    @PostMapping("/browser-logins/{id}/claim")
    public ResponseEntity<BrowserLoginService.Job> claim(@PathVariable UUID id,@Valid @RequestBody BrowserLoginService.ClaimRequest body,
            Authentication auth,HttpServletRequest http) { check(http);return result(service.claim(user(auth),id,body.helperId())); }
    @PostMapping("/browser-logins/{id}/complete")
    public ResponseEntity<BrowserLoginService.Job> complete(@PathVariable UUID id,@Valid @RequestBody BrowserLoginService.CompleteRequest body,
            Authentication auth,HttpServletRequest http) { check(http);return result(service.complete(user(auth),id,body)); }
    @PostMapping("/browser-logins/{id}/cancel")
    public ResponseEntity<BrowserLoginService.Job> cancel(@PathVariable UUID id,Authentication auth,HttpServletRequest http) { check(http);return result(service.cancel(user(auth),id)); }
    private void check(HttpServletRequest http) { if(!transport.isAllowed(http))throw new DomainException("OFFICIAL_HTTPS_REQUIRED","HTTPS or explicit local preview is required",HttpStatus.BAD_REQUEST); }
    private UUID user(Authentication auth) { return (UUID)auth.getPrincipal(); }
    private <T> ResponseEntity<T> result(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
