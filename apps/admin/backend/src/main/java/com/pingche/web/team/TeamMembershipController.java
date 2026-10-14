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
public class TeamMembershipController {
    private final TeamMembershipService memberships;

    public TeamMembershipController(TeamMembershipService memberships) {
        this.memberships = memberships;
    }

    @PutMapping("/{teamId}/members")
    public TeamResponse update(@PathVariable UUID teamId, @Valid @RequestBody TeamMembersUpdateRequest request) {
        return memberships.update(teamId, request);
    }
}
