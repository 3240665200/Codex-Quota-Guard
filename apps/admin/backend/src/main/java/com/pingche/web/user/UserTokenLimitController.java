package com.pingche.web.user;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserTokenLimitController {

    private final UserTokenLimitService service;

    public UserTokenLimitController(UserTokenLimitService service) {
        this.service = service;
    }

    @PutMapping("/{id}/token-limits")
    public UserResponse update(@PathVariable("id") UUID id,
            @Valid @RequestBody UserTokenLimitRequest request) {
        return service.update(id, request);
    }
}
