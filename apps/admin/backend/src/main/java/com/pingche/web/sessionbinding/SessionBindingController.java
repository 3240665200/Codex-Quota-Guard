package com.pingche.web.sessionbinding;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
    public ResponseEntity<?> create(Authentication auth, @Valid @RequestBody CreateRequest body, HttpServletRequest http) {
        requireTransport(http);
        return attemptResponse(service.create(user(auth), body.deviceId(), body.enrollmentId(), body.officialAccountId()), http);
    }
    @GetMapping("/attempts/{id}")
    public ResponseEntity<?> get(Authentication auth, @PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); return attemptResponse(service.get(user(auth), id), http);
    }
    @GetMapping("/current")
    public ResponseEntity<?> current(Authentication auth, @RequestParam UUID deviceId, @RequestParam UUID enrollmentId, HttpServletRequest http) {
        requireTransport(http);
        SessionBindingCurrentResponse current = service.current(user(auth), deviceId, enrollmentId);
        return queueMetadata(http) ? response(current) : response(new LegacyCurrent(legacy(current.attempt()), current.binding()));
    }
    @PostMapping("/attempts/{id}/prepare")
    public ResponseEntity<?> prepare(Authentication auth, @PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); return attemptResponse(service.prepare(user(auth), id), http);
    }
    @PostMapping("/attempts/{id}/detect")
    public ResponseEntity<?> detect(Authentication auth, @PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); return attemptResponse(service.detect(user(auth), id), http);
    }
    @PostMapping("/attempts/{id}/confirm")
    public ResponseEntity<?> confirm(Authentication auth, @PathVariable UUID id, @Valid @RequestBody ConfirmRequest body, HttpServletRequest http) {
        requireTransport(http); return attemptResponse(service.confirm(user(auth), id, body.sessionId()), http);
    }
    @PostMapping("/attempts/{id}/cancel")
    public ResponseEntity<?> cancel(Authentication auth, @PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); return attemptResponse(service.cancel(user(auth), id), http);
    }
    @PostMapping("/bindings/{id}/release")
    public ResponseEntity<Void> release(Authentication auth, @PathVariable UUID id, HttpServletRequest http) {
        requireTransport(http); service.release(user(auth), id); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private UUID user(Authentication authentication) { return (UUID) authentication.getPrincipal(); }
    private void requireTransport(HttpServletRequest request) { if (!transport.isAllowed(request)) throw new DomainException("SESSION_BINDING_HTTPS_REQUIRED", "HTTPS is required for device session binding", HttpStatus.BAD_REQUEST); }
    private <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
    private boolean queueMetadata(HttpServletRequest http) { return "2".equals(http.getHeader("X-CQG-Queue-Version")); }
    private ResponseEntity<?> attemptResponse(SessionBindingAttemptResponse attempt, HttpServletRequest http) {
        return queueMetadata(http) ? response(attempt) : response(legacy(attempt));
    }
    private LegacyAttempt legacy(SessionBindingAttemptResponse value) {
        return value == null ? null : new LegacyAttempt(value.id(), value.deviceId(), value.enrollmentId(), value.officialAccountId(),
                value.status(), value.queuePosition(), value.loginAllowed(), value.candidateSessionId(), value.candidateDisplayName(),
                value.candidatePlatform(), value.expiresAt(), value.errorCode(), value.quarantined(), value.binding());
    }
    // Older desktop bridges deliberately reject unknown JSON properties. Keep
    // their exact shape unless they explicitly opt into queue metadata v2.
    public record LegacyAttempt(UUID id, UUID deviceId, UUID enrollmentId, UUID officialAccountId,
            String status, int queuePosition, boolean loginAllowed, String candidateSessionId,
            String candidateDisplayName, String candidatePlatform, Instant expiresAt, String errorCode,
            boolean quarantined, SessionBindingResponse binding) { }
    public record LegacyCurrent(LegacyAttempt attempt, SessionBindingResponse binding) { }
    public record CreateRequest(@NotNull UUID deviceId, @NotNull UUID enrollmentId, UUID officialAccountId) { }
    public record ConfirmRequest(@NotBlank @Size(max=512) String sessionId) { }
}
