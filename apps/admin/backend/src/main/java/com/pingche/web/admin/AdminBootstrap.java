package com.pingche.web.admin;

import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final int MINIMUM_PASSWORD_LENGTH = 12;
    private static final int MAXIMUM_DISPLAY_NAME_LENGTH = 120;
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[^@\\s]+@[^@\\s]+\\.[^@\\s]+"
    );

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String configuredEmail;
    private final String configuredPassword;
    private final String configuredDisplayName;

    public AdminBootstrap(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            @Value("${app.admin-bootstrap-email:}") String configuredEmail,
            @Value("${app.admin-bootstrap-password:}") String configuredPassword,
            @Value("${app.admin-bootstrap-display-name:Administrator}") String configuredDisplayName
    ) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.configuredEmail = configuredEmail;
        this.configuredPassword = configuredPassword;
        this.configuredDisplayName = configuredDisplayName;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        boolean hasEmail = StringUtils.hasText(configuredEmail);
        boolean hasPassword = StringUtils.hasText(configuredPassword);
        if (hasEmail != hasPassword) {
            throw new IllegalStateException(
                    "ADMIN_BOOTSTRAP_EMAIL and ADMIN_BOOTSTRAP_PASSWORD must be configured together"
            );
        }
        if (!hasEmail) {
            return;
        }

        String email = normalizeEmail(configuredEmail);
        if (configuredPassword.length() < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "ADMIN_BOOTSTRAP_PASSWORD must be at least 12 characters"
            );
        }
        String displayName = normalizeDisplayName(configuredDisplayName);

        users.findByEmail(email).ifPresentOrElse(existingUser -> {
            if (existingUser.getRole() != User.Role.ADMIN) {
                throw new IllegalStateException(
                        "ADMIN_BOOTSTRAP_EMAIL belongs to a user who is not an administrator"
                );
            }
        }, () -> createFirstAdministrator(email, displayName));
    }

    private void createFirstAdministrator(String email, String displayName) {
        if (users.existsByRole(User.Role.ADMIN)) {
            return;
        }
        User administrator = User.bootstrapAdministrator(
                email,
                passwordEncoder.encode(configuredPassword),
                displayName
        );
        users.saveAndFlush(administrator);
    }

    private String normalizeEmail(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        if (normalized.length() > 320 || !EMAIL_PATTERN.matcher(normalized).matches()) {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_EMAIL must be a valid email");
        }
        return normalized;
    }

    private String normalizeDisplayName(String displayName) {
        if (displayName == null) {
            throw new IllegalStateException("ADMIN_BOOTSTRAP_DISPLAY_NAME must not be blank");
        }
        String normalized = displayName.trim();
        if (normalized.isEmpty() || normalized.length() > MAXIMUM_DISPLAY_NAME_LENGTH) {
            throw new IllegalStateException(
                    "ADMIN_BOOTSTRAP_DISPLAY_NAME must be between 1 and 120 characters"
            );
        }
        return normalized;
    }
}
