package com.pingche.web.mail;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/client/login-assistance")
public class ClientMailAssistanceController {
    private final MailAssistanceService service;
    private final LoginAssistanceEvents events;
    public ClientMailAssistanceController(MailAssistanceService service, LoginAssistanceEvents events) {
        this.service = service; this.events = events;
    }
    @GetMapping
    public ResponseEntity<LoginAssistanceResponse> availability(Authentication auth) { return noStore(service.availability((UUID) auth.getPrincipal())); }
    @PostMapping("/listeners")
    public ResponseEntity<MailListenerResponse> start(Authentication auth) { return noStore(service.start((UUID) auth.getPrincipal())); }
    @GetMapping("/listeners/current")
    public ResponseEntity<MailListenerCurrentResponse> current(Authentication auth) { return noStore(service.current((UUID) auth.getPrincipal())); }
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(Authentication auth, @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM).body(events.subscribe((UUID) auth.getPrincipal(), authorization.substring(7)));
    }
    @GetMapping("/listeners/{id}")
    public ResponseEntity<MailListenerResponse> poll(Authentication auth, @PathVariable UUID id) { return noStore(service.poll((UUID) auth.getPrincipal(), id)); }
    @DeleteMapping("/listeners/{id}")
    public ResponseEntity<MailListenerResponse> cancel(Authentication auth, @PathVariable UUID id) { return noStore(service.cancel((UUID) auth.getPrincipal(), id)); }
    private <T> ResponseEntity<T> noStore(T response) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response); }
}
