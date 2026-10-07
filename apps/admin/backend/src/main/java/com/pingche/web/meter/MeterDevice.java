package com.pingche.web.meter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "meter_devices")
public class MeterDevice {
    @Id private UUID id;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "token_hash", nullable = false, length = 64) private String tokenHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16) private Status status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "last_seen_at") private Instant lastSeenAt;
    @Version @Column(nullable = false) private long version;

    protected MeterDevice() { }

    public MeterDevice(UUID id, UUID userId, String tokenHash, Instant now, Instant expiresAt) {
        this.id = id;
        this.userId = userId;
        this.tokenHash = tokenHash;
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.expiresAt = expiresAt;
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getTokenHash() { return tokenHash; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }

    public void rotate(String replacementHash, Instant expiration) {
        this.tokenHash = replacementHash;
        this.expiresAt = expiration;
        this.lastSeenAt = null;
        this.status = Status.ACTIVE;
    }

    public void seen(Instant now) { this.lastSeenAt = now; }

    public void revoke() { this.status = Status.REVOKED; }

    public enum Status { ACTIVE, REVOKED }
}
