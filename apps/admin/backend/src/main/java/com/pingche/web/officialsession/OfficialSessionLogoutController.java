package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/official-accounts/{id}/session-logout")
@PreAuthorize("hasRole('ADMIN')")
public class OfficialSessionLogoutController {
    private final OfficialSessionLogoutService service;
    private final ManagementTransportGuard transport;

    public OfficialSessionLogoutController(OfficialSessionLogoutService service, ManagementTransportGuard transport) {
        this.service = service;
        this.transport = transport;
    }

    @PostMapping("/preview")
    public ResponseEntity<OfficialSessionLogoutPreview> preview(@PathVariable UUID id, @Valid @RequestBody PreviewRequest body,
            Authentication auth, HttpServletRequest http) {
        check(http);
        return result(service.preview((UUID) auth.getPrincipal(), id, body.mode(), body.sessionIds()));
    }

    @GetMapping("/current")
    public ResponseEntity<OfficialSessionLogoutService.CurrentResponse> current(@PathVariable UUID id,
            Authentication auth, HttpServletRequest http) {
        check(http);
        return result(service.current((UUID) auth.getPrincipal(), id));
    }

    @GetMapping("/{planId}")
    public ResponseEntity<OfficialSessionLogoutResult> result(@PathVariable UUID id, @PathVariable UUID planId,
            Authentication auth, HttpServletRequest http) {
        check(http);
        return result(service.result((UUID) auth.getPrincipal(), id, planId));
    }

    @PostMapping("/confirm")
    public ResponseEntity<OfficialSessionLogoutResult> confirm(@PathVariable UUID id, @Valid @RequestBody ConfirmRequest body,
            Authentication auth, HttpServletRequest http) {
        check(http);
        return result(service.confirm((UUID) auth.getPrincipal(), id, body.planId(), Boolean.TRUE.equals(body.acknowledgeLogoutRisk())));
    }

    @PostMapping("/{planId}/acknowledge-unknown")
    public ResponseEntity<OfficialSessionLogoutResult> acknowledge(@PathVariable UUID id, @PathVariable UUID planId,
            @RequestBody AcknowledgeRequest body, Authentication auth, HttpServletRequest http) {
        check(http);
        return result(service.acknowledgeUnknown((UUID) auth.getPrincipal(), id, planId, Boolean.TRUE.equals(body.acknowledgeUncertainOutcome())));
    }

    private void check(HttpServletRequest http) {
        if (!transport.isAllowed(http)) throw new DomainException("OFFICIAL_HTTPS_REQUIRED", "HTTPS is required for session logout", HttpStatus.BAD_REQUEST);
    }
    private <T> ResponseEntity<T> result(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    public record PreviewRequest(@NotNull @Pattern(regexp = "ALL|UNBOUND|SELECTED") String mode, List<String> sessionIds) { }
    public record ConfirmRequest(@NotNull UUID planId, Boolean acknowledgeLogoutRisk) { }
    public record AcknowledgeRequest(Boolean acknowledgeUncertainOutcome) { }
}
