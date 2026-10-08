package com.pingche.web.quota;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/official-accounts")
@PreAuthorize("hasRole('ADMIN')")
public class OfficialAccountController {
    private final OfficialAccountService accounts;

    public OfficialAccountController(OfficialAccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public ResponseEntity<List<OfficialAccountResponse>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.list());
    }

    @PostMapping
    public ResponseEntity<OfficialAccountResponse> create(@Valid @RequestBody OfficialAccountRequest request) {
        OfficialAccountResponse created = accounts.create(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/official-accounts/" + created.id()))
                .cacheControl(CacheControl.noStore()).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<OfficialAccountResponse> update(@PathVariable UUID id,
            @Valid @RequestBody OfficialAccountRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        accounts.delete(id);
        return ResponseEntity.noContent().build();
    }
}
