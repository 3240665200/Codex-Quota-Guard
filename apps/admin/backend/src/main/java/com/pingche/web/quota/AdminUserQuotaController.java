package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.UserRepository;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserQuotaController {

    private final QuotaOverviewService service;
    private final UserRepository users;
    private final QuotaResetService resets;

    public AdminUserQuotaController(QuotaOverviewService service, UserRepository users,
            QuotaResetService resets) {
        this.service = service;
        this.users = users;
        this.resets = resets;
    }

    @GetMapping("/{id}/quota-overview")
    public ResponseEntity<QuotaOverviewResponse> get(@PathVariable("id") UUID id) {
        if (!users.existsById(id)) {
            throw new DomainException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.overview(id));
    }

    @PostMapping("/{id}/quota-reset")
    public ResponseEntity<Void> reset(@PathVariable("id") UUID id) {
        resets.resetUser(id);
        return ResponseEntity.noContent().build();
    }
}
