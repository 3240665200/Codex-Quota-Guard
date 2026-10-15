package com.pingche.web.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private final SecretKey key;
    private final Duration lifetime;
    private final String issuer;

    public JwtService(
            @Value("${app.jwt-secret}") String secret,
            @Value("${app.access-token-ttl}") Duration lifetime,
            @Value("${app.jwt-issuer}") String issuer
    ) {
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes");
        }
        if (issuer.isBlank()) {
            throw new IllegalStateException("JWT_ISSUER must not be blank");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.lifetime = lifetime;
        this.issuer = issuer;
    }

    public Duration getLifetime() {
        return lifetime;
    }

    public String create(UUID userId, String role) {
        return create(userId, role, 0L);
    }

    public String create(UUID userId, String role, long sessionVersion) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claim("role", role)
                .claim("sessionVersion", sessionVersion)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(lifetime)))
                .signWith(key)
                .compact();
    }

    public UUID parseUserId(String token) {
        return parseIdentity(token).userId();
    }

    public JwtIdentity parseIdentity(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        Number sessionVersion = claims.get("sessionVersion", Number.class);
        if (sessionVersion == null || sessionVersion.longValue() < 0) {
            throw new IllegalArgumentException("Access token session version is invalid");
        }
        if (claims.getExpiration() == null) throw new IllegalArgumentException("Access token expiration is required");
        return new JwtIdentity(
                UUID.fromString(claims.getSubject()),
                sessionVersion.longValue(),
                claims.getExpiration().toInstant()
        );
    }

    public record JwtIdentity(UUID userId, long sessionVersion, Instant expiresAt) {
    }
}
