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
    private final MeterPairingService pairing;

    public SystemSessionController(
            SystemSessionService sessionService,
            MeterPairingService pairing
    ) {
        this.sessionService = sessionService;
        this.pairing = pairing;
    }

    @PostMapping("/login")
    public SystemSessionOverview login(@Valid @RequestBody LoginRequest request) {
        return paired(sessionService.loginProbe(request.email(), request.password()));
    }

    private SystemSessionOverview paired(SystemSessionService.Probe probe) {
        if (!probe.overview().authenticated()) return probe.overview();
        sessionService.requireCurrent(probe);
        try {
            pairing.pairIfInstalled();
        } catch (ControlPlaneUnavailableException | SessionStorageException | SystemSessionRequiredException exception) {
            // A transient outage cannot prove the remembered credential revoked.
            throw exception;
        } catch (RuntimeException exception) {
            // An installed meter belongs to exactly one system user. Do not
            // display another user's quota while the service bills the first.
            sessionService.logoutIfGeneration(probe.generation());
            throw new MeterPairingRequiredException();
        }
        sessionService.requireCurrent(probe);
        return probe.overview();
    }

    @GetMapping("/current")
    public SystemSessionOverview current() {
        return paired(sessionService.currentProbe());
    }

    @GetMapping("/access")
    public org.springframework.http.ResponseEntity<com.codexquotaguard.backend.controlplane.ClientAccess> access() {
        return org.springframework.http.ResponseEntity.ok().header("Cache-Control", "no-store").body(sessionService.clientAccess());
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
