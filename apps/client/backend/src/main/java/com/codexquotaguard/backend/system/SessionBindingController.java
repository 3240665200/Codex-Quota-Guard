package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.controlplane.SessionBindingAttempt;
import com.codexquotaguard.backend.controlplane.SessionBindingCurrent;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/session-bindings")
public final class SessionBindingController {
    private final SessionBindingService service;
    public SessionBindingController(SessionBindingService service) { this.service = service; }
    @GetMapping public ResponseEntity<SessionBindingCurrent> current() { return noStore(service.current()); }
    @PostMapping("/attempts") public ResponseEntity<SessionBindingAttempt> start() { return noStore(service.start()); }
    @GetMapping("/attempts/{id}") public ResponseEntity<SessionBindingAttempt> status(@PathVariable UUID id) { return noStore(service.status(id)); }
    @PostMapping("/attempts/{id}/prepare") public ResponseEntity<SessionBindingAttempt> prepare(@PathVariable UUID id) { return noStore(service.action(id, "prepare")); }
    @PostMapping("/attempts/{id}/detect") public ResponseEntity<SessionBindingAttempt> detect(@PathVariable UUID id) { return noStore(service.action(id, "detect")); }
    @PostMapping("/attempts/{id}/confirm") public ResponseEntity<SessionBindingAttempt> confirm(@PathVariable UUID id) { return noStore(service.action(id, "confirm")); }
    @PostMapping("/attempts/{id}/cancel") public ResponseEntity<SessionBindingAttempt> cancel(@PathVariable UUID id) { return noStore(service.action(id, "cancel")); }
    private static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
