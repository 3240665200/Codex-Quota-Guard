package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import com.pingche.web.weblogin.ManagedLoginBrowser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class WebLoginController {
    private final WebLoginService service;
    private final ManagementTransportGuard transport;

    public WebLoginController(WebLoginService service,ManagementTransportGuard transport) {this.service=service;this.transport=transport;}

    public record InputRequest(String type,Integer x,Integer y,Integer deltaY,String key,String text,Boolean ctrl,Boolean shift) {
        ManagedLoginBrowser.Input toInput() {return new ManagedLoginBrowser.Input(type,x,y,deltaY,key,text,Boolean.TRUE.equals(ctrl),Boolean.TRUE.equals(shift));}
        @Override public String toString() {return "WebLoginInputRequest[redacted]";}
    }

    @PostMapping("/official-accounts/{accountId}/web-login")
    public ResponseEntity<WebLoginService.Job> create(@PathVariable UUID accountId,@Valid @RequestBody BrowserLoginService.StartRequest body,
            Authentication auth,HttpServletRequest http) {check(http);return result(service.create(user(auth),accountId,body));}

    @GetMapping("/web-logins/{id}")
    public ResponseEntity<WebLoginService.Job> get(@PathVariable UUID id,Authentication auth,HttpServletRequest http) {
        check(http);return result(service.get(user(auth),id));
    }

    @GetMapping("/web-logins/{id}/frame")
    public ResponseEntity<ManagedLoginBrowser.Frame> frame(@PathVariable UUID id,Authentication auth,HttpServletRequest http) {
        check(http);return result(service.frame(user(auth),id));
    }

    @PostMapping("/web-logins/{id}/input")
    public ResponseEntity<Void> input(@PathVariable UUID id,@RequestBody InputRequest body,Authentication auth,HttpServletRequest http) {
        check(http);service.input(user(auth),id,body.toInput());return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/web-logins/{id}/complete")
    public ResponseEntity<WebLoginService.Job> complete(@PathVariable UUID id,Authentication auth,HttpServletRequest http) {
        check(http);return result(service.complete(user(auth),id));
    }

    @PostMapping("/web-logins/{id}/cancel")
    public ResponseEntity<WebLoginService.Job> cancel(@PathVariable UUID id,Authentication auth,HttpServletRequest http) {
        check(http);return result(service.cancel(user(auth),id));
    }

    private void check(HttpServletRequest http) {
        if(!transport.isAllowed(http))throw new DomainException("OFFICIAL_HTTPS_REQUIRED","HTTPS or explicit local preview is required",HttpStatus.BAD_REQUEST);
    }
    private UUID user(Authentication authentication) {return (UUID)authentication.getPrincipal();}
    private <T>ResponseEntity<T> result(T value) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);}
}
