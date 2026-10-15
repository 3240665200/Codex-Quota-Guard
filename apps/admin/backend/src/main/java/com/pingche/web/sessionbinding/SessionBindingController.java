package com.pingche.web.sessionbinding;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/client/session-bindings")
@PreAuthorize("hasAnyRole('USER','ADMIN')")
public class SessionBindingController {
    private final SessionBindingService service;
    private final ManagementTransportGuard transport;
    public SessionBindingController(SessionBindingService service, ManagementTransportGuard transport) { this.service = service; this.transport = transport; }

    @PostMapping("/attempts")
    public ResponseEntity<SessionBindingAttemptResponse> create(Authentication auth, @Valid @RequestBody CreateRequest body, HttpServletRequest http) {
        requireTransport(http);
        return response(service.create(user(auth), body.deviceId(), body.enrollmentId(), body.officialAccountId()));
    }
    @GetMapping("/attempts/{id}")
    public ResponseEntity<SessionBindingAttemptResponse> get(Authentication auth, @PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); return response(service.get(user(auth), id));
    }
    @GetMapping("/current")
    public ResponseEntity<SessionBindingCurrentResponse> current(Authentication auth, @RequestParam UUID deviceId, @RequestParam UUID enrollmentId, HttpServletRequest http) {
        requireTransport(http); return response(service.current(user(auth), deviceId, enrollmentId));
    }
    @PostMapping("/attempts/{id}/prepare")
    public ResponseEntity<SessionBindingAttemptResponse> prepare(Authentication auth, @PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); return response(service.prepare(user(auth), id));
    }
    @PostMapping("/attempts/{id}/detect")
    public ResponseEntity<SessionBindingAttemptResponse> detect(Authentication auth, @PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); return response(service.detect(user(auth), id));
    }
    @PostMapping("/attempts/{id}/confirm")
    public ResponseEntity<SessionBindingAttemptResponse> confirm(Authentication auth, @PathVariable UUID id, @Valid @RequestBody ConfirmRequest body, HttpServletRequest http) {
        requireTransport(http); return response(service.confirm(user(auth), id, body.sessionId()));
    }
    @PostMapping("/attempts/{id}/cancel")
    public ResponseEntity<SessionBindingAttemptResponse> cancel(Authentication auth, @PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); return response(service.cancel(user(auth), id));
    }
    @PostMapping("/bindings/{id}/release")
    public ResponseEntity<Void> release(Authentication auth, @PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); service.release(user(auth), id); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private UUID user(Authentication authentication) { return (UUID) authentication.getPrincipal(); }
    private void requireTransport(HttpServletRequest request) { if (!transport.isAllowed(request)) throw new DomainException("SESSION_BINDING_HTTPS_REQUIRED", "HTTPS is required for device session binding", HttpStatus.BAD_REQUEST); }
    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    public record CreateRequest(@NotNull UUID deviceId, @NotNull UUID enrollmentId, UUID officialAccountId) { }
    public record ConfirmRequest(@NotBlank @Size(max=512) String sessionId) { }
}
