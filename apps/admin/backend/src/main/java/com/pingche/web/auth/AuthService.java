package com.pingche.web.auth;

import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import com.pingche.web.user.UserResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Duration refreshLifetime;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            UserRepository users,
            RefreshSessionRepository sessions,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            @Value("${app.refresh-token-ttl}") Duration refreshLifetime
    ) {
        this.users = users;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshLifetime = refreshLifetime;
    }

    @Transactional
    public AuthResponse register(String email, String password, String displayName) {
        String normalizedEmail = normalizeEmail(email);
        if (users.existsByEmail(normalizedEmail)) {
            throw emailExists();
        }

        User user = new User(
                normalizedEmail,
                passwordEncoder.encode(password),
                requireDisplayName(displayName)
        );
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException exception) {
            throw emailExists();
        }
        return issue(user, UUID.randomUUID());
    }

    @Transactional
    public AuthResponse login(String email, String password) {
        User user = users.findByEmail(normalizeEmail(email))
                .orElseThrow(this::invalidCredentials);
        if (user.getStatus() == User.Status.DISABLED) {
            throw accountDisabled();
        }
        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            throw invalidCredentials();
        }
        return issue(user, UUID.randomUUID());
    }

    @Transactional(noRollbackFor = DomainException.class)
    public AuthResponse refresh(String rawToken) {
        Instant now = Instant.now();
        RefreshSession current = lockSessionFamily(rawToken);

        if (!current.isUsable(now)) {
            sessions.revokeFamily(current.getFamilyId(), now);
            throw invalidRefreshToken();
        }

        User user = users.findById(current.getUserId())
                .orElseThrow(this::invalidRefreshToken);
        if (user.getStatus() == User.Status.DISABLED) {
            sessions.revokeFamily(current.getFamilyId(), now);
            throw accountDisabled();
        }
        if (current.getSessionVersion() != user.getSessionVersion()) {
            sessions.revokeFamily(current.getFamilyId(), now);
            throw invalidRefreshToken();
        }

        IssuedSession replacement = newSession(user, current.getFamilyId(), now);
        current.revoke(now, replacement.session().getId());
        sessions.save(replacement.session());
        return response(user, replacement.rawToken());
    }

    @Transactional
    public void logout(UUID authenticatedUserId, String rawToken) {
        String tokenHash = hashToken(rawToken);
        sessions.findFamilyIdByTokenHash(tokenHash).ifPresent(familyId -> {
            sessions.findFirstByFamilyIdOrderByCreatedAtAscIdAsc(familyId);
            sessions.findByTokenHashForUpdate(tokenHash)
                    .filter(session -> session.getUserId().equals(authenticatedUserId))
                    .ifPresent(session -> sessions.revokeFamily(
                            session.getFamilyId(),
                            Instant.now()
                    ));
        });
    }

    private RefreshSession lockSessionFamily(String rawToken) {
        String tokenHash = hashToken(rawToken);
        UUID familyId = sessions.findFamilyIdByTokenHash(tokenHash)
                .orElseThrow(this::invalidRefreshToken);

        // Every generation locks the same oldest family row before inspecting the
        // presented token. This serializes refresh, replay revocation and logout.
        sessions.findFirstByFamilyIdOrderByCreatedAtAscIdAsc(familyId)
                .orElseThrow(this::invalidRefreshToken);
        return sessions.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(this::invalidRefreshToken);
    }

    private AuthResponse issue(User user, UUID familyId) {
        IssuedSession issued = newSession(user, familyId, Instant.now());
        sessions.save(issued.session());
        return response(user, issued.rawToken());
    }

    private IssuedSession newSession(User user, UUID familyId, Instant now) {
        String rawToken = newRawToken();
        RefreshSession session = new RefreshSession(
                UUID.randomUUID(),
                user.getId(),
                familyId,
                hashToken(rawToken),
                now.plus(refreshLifetime),
                now,
                user.getSessionVersion()
        );
        return new IssuedSession(rawToken, session);
    }

    private AuthResponse response(User user, String refreshToken) {
        return new AuthResponse(
                jwtService.create(
                        user.getId(),
                        user.getRole().name(),
                        user.getSessionVersion()
                ),
                "Bearer",
                jwtService.getLifetime().toSeconds(),
                refreshToken,
                refreshLifetime.toSeconds(),
                UserResponse.from(user)
        );
    }

    private String newRawToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String requireDisplayName(String displayName) {
        String normalized = displayName.trim();
        if (normalized.isEmpty()) {
            throw new DomainException(
                    "VALIDATION_ERROR",
                    "displayName must not be blank",
                    HttpStatus.BAD_REQUEST
            );
        }
        return normalized;
    }

    private DomainException emailExists() {
        return new DomainException(
                "AUTH_EMAIL_EXISTS",
                "Email is already registered",
                HttpStatus.CONFLICT
        );
    }

    private DomainException invalidCredentials() {
        return new DomainException(
                "AUTH_INVALID_CREDENTIALS",
                "Email or password is incorrect",
                HttpStatus.UNAUTHORIZED
        );
    }

    private DomainException invalidRefreshToken() {
        return new DomainException(
                "AUTH_REFRESH_INVALID",
                "Refresh token is invalid or expired",
                HttpStatus.UNAUTHORIZED
        );
    }

    private DomainException accountDisabled() {
        return new DomainException(
                "AUTH_ACCOUNT_DISABLED",
                "Account is disabled",
                HttpStatus.FORBIDDEN
        );
    }

    private record IssuedSession(String rawToken, RefreshSession session) {
    }
}
