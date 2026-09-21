package com.pingche.web.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(
                request.email(),
                request.password(),
                request.displayName()
        );
        return ResponseEntity.status(201).body(response);
    }

    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password());
    }

    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            @Valid @RequestBody RefreshRequest request
    ) {
        authService.logout((UUID) authentication.getPrincipal(), request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    public record RegisterRequest(
            @NotBlank
            @Size(max = 320)
            @Pattern(regexp = "\\s*[^@\\s]+@[^@\\s]+\\.[^@\\s]+\\s*")
            String email,
            @NotBlank @Size(min = 12, max = 200) String password,
            @NotBlank @Size(max = 120) String displayName
    ) {
    }

    public record LoginRequest(
            @NotBlank
            @Size(max = 320)
            @Pattern(regexp = "\\s*[^@\\s]+@[^@\\s]+\\.[^@\\s]+\\s*")
            String email,
            @NotBlank @Size(max = 200) String password
    ) {
    }

    public record RefreshRequest(@NotBlank @Size(max = 500) String refreshToken) {
    }
}
