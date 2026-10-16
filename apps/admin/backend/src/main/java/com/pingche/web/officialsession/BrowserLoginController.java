package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.ManagementTransportGuard;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class BrowserLoginController {
    private final ManagementTransportGuard transport;
    public BrowserLoginController(ManagementTransportGuard transport) { this.transport=transport; }

    @GetMapping({"/browser-logins", "/browser-logins/{id}"})
    public void readRetired(HttpServletRequest http) { retired(http); }

    @PostMapping({"/official-accounts/{accountId}/browser-login", "/browser-logins/{id}/claim",
            "/browser-logins/{id}/complete", "/browser-logins/{id}/cancel"})
    public void writeRetired(HttpServletRequest http) { retired(http); }

    private void retired(HttpServletRequest http) {
        if(!transport.isAllowed(http)) throw new DomainException("OFFICIAL_HTTPS_REQUIRED",
                "Request transport is not permitted by the server configuration", HttpStatus.BAD_REQUEST);
        throw new DomainException("OFFICIAL_LOGIN_HELPER_RETIRED", "The desktop official login helper has been retired", HttpStatus.GONE);
    }
}
