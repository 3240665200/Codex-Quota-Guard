package com.pingche.web.user;

import com.pingche.web.auth.DomainException;
import java.util.Locale;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;

    public UserService(UserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public UserResponse current(UUID userId) {
        return UserResponse.from(requireActive(userId));
    }

    @Transactional
    public UserResponse update(UUID userId, String requestedEmail, String requestedDisplayName) {
        User user = requireActive(userId);
        if (requestedEmail == null && requestedDisplayName == null) {
            throw validation("At least one profile field is required");
        }

        String email = requestedEmail == null
                ? user.getEmail()
                : normalizeEmail(requestedEmail);
        String displayName = requestedDisplayName == null
                ? user.getDisplayName()
                : normalizeDisplayName(requestedDisplayName);

        if (!email.equals(user.getEmail()) && users.existsByEmailAndIdNot(email, userId)) {
            throw emailExists();
        }

        user.updateProfile(email, displayName);
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw emailExists();
        }
        return UserResponse.from(user);
    }

    private User requireActive(UUID userId) {
        User user = users.findById(userId).orElseThrow(() -> new DomainException(
                "AUTH_TOKEN_INVALID",
                "Access token is invalid",
                HttpStatus.UNAUTHORIZED
        ));
        if (user.getStatus() == User.Status.DISABLED) {
            throw new DomainException(
                    "AUTH_ACCOUNT_DISABLED",
                    "Account is disabled",
                    HttpStatus.FORBIDDEN
            );
        }
        return user;
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeDisplayName(String displayName) {
        String normalized = displayName.trim();
        if (normalized.isEmpty()) {
            throw validation("displayName must not be blank");
        }
        return normalized;
    }

    private DomainException validation(String message) {
        return new DomainException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }

    private DomainException emailExists() {
        return new DomainException(
                "AUTH_EMAIL_EXISTS",
                "Email is already registered",
                HttpStatus.CONFLICT
        );
    }
}
