package com.pingche.web.team;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/teams")
@PreAuthorize("hasRole('ADMIN')")
public class TeamController {

    private final TeamService teams;

    public TeamController(TeamService teams) {
        this.teams = teams;
    }

    @GetMapping
    public List<TeamResponse> list() {
        return teams.list();
    }

    @GetMapping("/{id}")
    public TeamResponse get(@PathVariable("id") UUID id) {
        return teams.get(id);
    }

    @PostMapping
    public ResponseEntity<TeamResponse> create(@Valid @RequestBody TeamCreateRequest request) {
        TeamResponse created = teams.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/teams/" + created.id()))
                .body(created);
    }

    @PatchMapping("/{id}")
    public TeamResponse patch(
            @PathVariable("id") UUID id,
            @Valid @RequestBody TeamUpdateRequest request
    ) {
        return teams.update(id, request);
    }

    @PutMapping("/{id}")
    public TeamResponse put(
            @PathVariable("id") UUID id,
            @Valid @RequestBody TeamUpdateRequest request
    ) {
        return teams.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        teams.delete(id);
        return ResponseEntity.noContent().build();
    }
}
