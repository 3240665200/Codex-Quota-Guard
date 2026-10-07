package com.pingche.web.team;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/teams")
@PreAuthorize("hasRole('ADMIN')")
public class TeamTokenLimitController {

    private final TeamTokenLimitService service;

    public TeamTokenLimitController(TeamTokenLimitService service) {
        this.service = service;
    }

    @PutMapping("/{id}/token-limits")
    public TeamResponse update(@PathVariable("id") UUID id,
            @Valid @RequestBody TeamTokenLimitRequest request) {
        return service.update(id, request);
    }
}
