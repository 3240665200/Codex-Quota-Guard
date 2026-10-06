package com.pingche.web.codex;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Narrow client-facing view of the authenticated product account and its managed
 * Codex-account assignment. There is intentionally no credential download API.
 */
@RestController
@RequestMapping("/api/v1/client/account")
public class ClientAccountController {

    private final CodexAccountAssignmentService assignments;

    public ClientAccountController(CodexAccountAssignmentService assignments) {
        this.assignments = assignments;
    }

    @GetMapping("/overview")
    public ClientAccountOverviewResponse overview(Authentication authentication) {
        return assignments.currentOverview((UUID) authentication.getPrincipal());
    }
}
