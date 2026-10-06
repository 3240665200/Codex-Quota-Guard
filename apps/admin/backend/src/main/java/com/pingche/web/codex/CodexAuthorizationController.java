package com.pingche.web.codex;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/codex-accounts/authorizations")
@PreAuthorize("hasRole('ADMIN')")
public class CodexAuthorizationController {

    private final CodexAuthorizationService service;

    public CodexAuthorizationController(CodexAuthorizationService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<CodexAuthorizationResponse> start(Authentication authentication,
            @Valid @RequestBody StartRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.start((UUID) authentication.getPrincipal(), request.displayName(),
                        request.mode() == null ? CodexLoginMode.DEVICE_CODE : request.mode()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CodexAuthorizationResponse> get(Authentication authentication, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.get((UUID) authentication.getPrincipal(), id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(Authentication authentication, @PathVariable UUID id) {
        service.cancel((UUID) authentication.getPrincipal(), id);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    public record StartRequest(@NotBlank @Size(max = 120) String displayName, CodexLoginMode mode) { }
}
