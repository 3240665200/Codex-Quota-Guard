package com.codexquotaguard.backend.system;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Local loopback API for a product-user session, protected by Tauri's bearer token. */
@RestController
@RequestMapping("/api/v1/session")
public final class SystemSessionController {

    private final SystemSessionService sessionService;

    public SystemSessionController(SystemSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping("/login")
    public SystemSessionOverview login(@Valid @RequestBody LoginRequest request) {
        return sessionService.login(request.email(), request.password());
    }

    @GetMapping("/current")
    public SystemSessionOverview current() {
        return sessionService.currentOverview();
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout() {
        sessionService.logout();
    }

    public record LoginRequest(
            @NotBlank
            @Size(max = 320)
            @Pattern(regexp = "\\s*[^@\\s]+@[^@\\s]+\\.[^@\\s]+\\s*")
            String email,
            @NotBlank @Size(max = 200) String password
    ) {
    }
}
