package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.controlplane.LoginAssistanceInfo;
import com.codexquotaguard.backend.controlplane.LoginAssistanceListener;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Loopback-only bridge; the current product session supplies all authorization. */
@RestController
@RequestMapping("/api/v1/login-assistance")
public final class LoginAssistanceController {
    private final SystemSessionService sessions;
    public LoginAssistanceController(SystemSessionService sessions) { this.sessions = sessions; }

    @GetMapping
    public ResponseEntity<LoginAssistanceInfo> info() { return noStore(sessions.loginAssistanceInfo()); }

    @PostMapping("/listeners")
    public ResponseEntity<LoginAssistanceListener> start() { return noStore(sessions.startLoginAssistance()); }

    @GetMapping("/listeners/{id}")
    public ResponseEntity<LoginAssistanceListener> status(@PathVariable UUID id) {
        return noStore(sessions.loginAssistanceStatus(id));
    }

    @DeleteMapping("/listeners/{id}")
    public ResponseEntity<LoginAssistanceListener> cancel(@PathVariable UUID id) {
        return noStore(sessions.cancelLoginAssistance(id));
    }

    private static <T> ResponseEntity<T> noStore(T value) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
    }
}
