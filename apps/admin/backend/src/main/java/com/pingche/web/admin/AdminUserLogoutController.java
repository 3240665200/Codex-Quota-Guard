package com.pingche.web.admin;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users/{id}/force-logout-all")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserLogoutController {
    private final AdminUserLogoutService service;
    private final ManagementTransportGuard transport;

    public AdminUserLogoutController(AdminUserLogoutService service, ManagementTransportGuard transport) {
        this.service = service;
        this.transport = transport;
    }

    @PostMapping("/preview")
    public ResponseEntity<AdminUserLogoutService.Preview> preview(@PathVariable UUID id,
            Authentication auth, HttpServletRequest http) {
        check(http);
        return response(service.preview((UUID) auth.getPrincipal(), id));
    }

    @PostMapping("/confirm")
    public ResponseEntity<AdminUserLogoutService.Result> confirm(@PathVariable UUID id,
            @Valid @RequestBody ConfirmRequest body, Authentication auth, HttpServletRequest http) {
        check(http);
        return response(service.confirm((UUID) auth.getPrincipal(), id, body.planId(), Boolean.TRUE.equals(body.acknowledgeLogoutRisk())));
    }

    @GetMapping("/{planId}")
    public ResponseEntity<AdminUserLogoutService.Result> result(@PathVariable UUID id, @PathVariable UUID planId,
            Authentication auth, HttpServletRequest http) {
        check(http);
        return response(service.result((UUID) auth.getPrincipal(), id, planId));
    }

    @GetMapping("/current")
    public ResponseEntity<AdminUserLogoutService.CurrentResponse> current(@PathVariable UUID id,
            Authentication auth, HttpServletRequest http) {
        check(http);
        return response(service.current((UUID) auth.getPrincipal(), id));
    }

    private void check(HttpServletRequest http) {
        if (!transport.isAllowed(http)) throw new DomainException("OFFICIAL_HTTPS_REQUIRED", "HTTPS is required for session logout", HttpStatus.BAD_REQUEST);
    }
    private <T> ResponseEntity<T> response(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
    public record ConfirmRequest(@NotNull UUID planId, Boolean acknowledgeLogoutRisk) { }
}
