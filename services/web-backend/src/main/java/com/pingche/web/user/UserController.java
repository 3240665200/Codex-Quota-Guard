package com.pingche.web.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/me")
    public UserResponse current(Authentication authentication) {
        return userService.current((UUID) authentication.getPrincipal());
    }

    @PatchMapping("/me")
    public UserResponse update(
            Authentication authentication,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return userService.update(
                (UUID) authentication.getPrincipal(),
                request.email(),
                request.displayName()
        );
    }

    public record UpdateProfileRequest(
            @Size(max = 320)
            @Pattern(regexp = "\\s*[^@\\s]+@[^@\\s]+\\.[^@\\s]+\\s*")
            String email,
            @Size(max = 120) String displayName
    ) {
    }
}
