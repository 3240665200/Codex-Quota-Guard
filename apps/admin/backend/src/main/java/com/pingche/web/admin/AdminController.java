package com.pingche.web.admin;

import com.pingche.web.codex.CodexAccountAssignmentRequest;
import com.pingche.web.codex.CodexAccountAssignmentService;
import com.pingche.web.codex.ManagedCodexAccountResponse;
import com.pingche.web.quota.QuotaPolicyService;
import com.pingche.web.quota.QuotaPolicyUpdateRequest;
import com.pingche.web.user.User;
import com.pingche.web.user.UserResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    private final AdminService adminService;
    private final CodexAccountAssignmentService codexAccountAssignments;
    private final QuotaPolicyService quotaPolicies;

    public AdminController(
            AdminService adminService,
            CodexAccountAssignmentService codexAccountAssignments,
            QuotaPolicyService quotaPolicies
    ) {
        this.adminService = adminService;
        this.codexAccountAssignments = codexAccountAssignments;
        this.quotaPolicies = quotaPolicies;
    }

    @GetMapping("/dashboard")
    public AdminDashboardResponse dashboard() {
        return adminService.dashboard();
    }

    @GetMapping("/users")
    public AdminUserPageResponse listUsers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @RequestParam(required = false) @Size(max = 320) String q,
            @RequestParam(required = false) User.Status status,
            @RequestParam(required = false) User.Role role
    ) {
        return adminService.listUsers(page, size, q, status, role);
    }

    @GetMapping("/users/{id}")
    public UserResponse getUser(@PathVariable("id") UUID id) {
        return adminService.getUser(id);
    }

    @PostMapping("/users")
    public ResponseEntity<UserResponse> createUser(
            @Valid @RequestBody AdminUserCreateRequest request
    ) {
        UserResponse created = adminService.createUser(request);
        return ResponseEntity.created(
                URI.create("/api/v1/admin/users/" + created.id())
        ).body(created);
    }

    @PatchMapping("/users/{id}")
    public UserResponse updateUser(
            Authentication authentication,
            @PathVariable("id") UUID id,
            @Valid @RequestBody AdminUserUpdateRequest request
    ) {
        return adminService.updateUser(
                (UUID) authentication.getPrincipal(),
                id,
                request
        );
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Void> deleteUser(
            Authentication authentication,
            @PathVariable("id") UUID id
    ) {
        adminService.deleteUser((UUID) authentication.getPrincipal(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/force-logout")
    public ResponseEntity<Void> forceLogout(@PathVariable("id") UUID id) {
        adminService.forceLogout(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{id}/codex-account")
    public ManagedCodexAccountResponse assignCodexAccount(
            @PathVariable("id") UUID id,
            @Valid @RequestBody CodexAccountAssignmentRequest request
    ) {
        return codexAccountAssignments.assign(id, request.codexAccountId());
    }

    @DeleteMapping("/users/{id}/codex-account")
    public ResponseEntity<Void> unassignCodexAccount(@PathVariable("id") UUID id) {
        codexAccountAssignments.unassign(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{id}/quota-policy")
    public UserResponse updateQuotaPolicy(
            @PathVariable("id") UUID id,
            @Valid @RequestBody QuotaPolicyUpdateRequest request
    ) {
        return quotaPolicies.update(id, request);
    }
}
