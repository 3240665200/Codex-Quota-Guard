package com.pingche.web.mail;

import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminMailAssistanceController {
    private final MailAssistanceService service;
    private final MailProviderCatalog providers;
    public AdminMailAssistanceController(MailAssistanceService service, MailProviderCatalog providers) { this.service = service; this.providers = providers; }
    @GetMapping("/mail-assistance/providers")
    public ResponseEntity<List<MailProviderCatalog.Provider>> providers() { return noStore(providers.list()); }
    @GetMapping("/official-accounts/{id}/mailbox")
    public ResponseEntity<MailboxResponse> read(@PathVariable UUID id) { return noStore(service.readConfig(id)); }
    @PutMapping("/official-accounts/{id}/mailbox")
    public ResponseEntity<MailboxResponse> save(@PathVariable UUID id, @Valid @RequestBody MailboxRequest request) { return noStore(service.saveConfig(id, request)); }
    @DeleteMapping("/official-accounts/{id}/mailbox")
    public ResponseEntity<Void> delete(@PathVariable UUID id) { service.deleteConfig(id); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build(); }
    @PostMapping("/official-accounts/{id}/mailbox/test")
    public ResponseEntity<MailAssistanceService.TestResult> test(@PathVariable UUID id) { return noStore(service.testConfig(id)); }
    private <T> ResponseEntity<T> noStore(T response) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(response); }
}
