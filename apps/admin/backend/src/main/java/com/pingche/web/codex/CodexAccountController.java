package com.pingche.web.codex;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin/codex-accounts")
@PreAuthorize("hasRole('ADMIN')")
public class CodexAccountController {

    private final CodexAccountService service;

    public CodexAccountController(CodexAccountService service) {
        this.service = service;
    }

    @GetMapping
    public CodexAccountListResponse list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 320) String q,
            @RequestParam(required = false) CodexAccount.Status status
    ) {
        return service.list(page, size, q, status);
    }

    @GetMapping("/{id}")
    public CodexAccountResponse get(@PathVariable("id") UUID id) {
        return service.get(id);
    }

    @PostMapping
    public ResponseEntity<CodexAccountResponse> create(
            @Valid @RequestBody CodexAccountCreateRequest request
    ) {
        CodexAccountResponse created = service.create(request);
        return ResponseEntity.created(
                URI.create("/api/v1/admin/codex-accounts/" + created.id())
        ).body(created);
    }

    @PatchMapping("/{id}")
    public CodexAccountResponse update(
            @PathVariable("id") UUID id,
            @Valid @RequestBody CodexAccountUpdateRequest request
    ) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
