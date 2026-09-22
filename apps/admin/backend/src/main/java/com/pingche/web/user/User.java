package com.pingche.web.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "session_version", nullable = false)
    private long sessionVersion;

    protected User() {
    }

    public User(String email, String passwordHash, String displayName) {
        Instant now = Instant.now();
        this.id = UUID.randomUUID();
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        this.role = Role.USER;
        this.status = Status.ACTIVE;
        this.createdAt = now;
        this.updatedAt = now;
        this.sessionVersion = 0L;
    }

    public static User bootstrapAdministrator(
            String email,
            String passwordHash,
            String displayName
    ) {
        User user = new User(email, passwordHash, displayName);
        user.role = Role.ADMIN;
        return user;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Role getRole() {
        return role;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getSessionVersion() {
        return sessionVersion;
    }

    public void updateProfile(String email, String displayName) {
        this.email = email;
        this.displayName = displayName;
        this.updatedAt = Instant.now();
    }

    public void updatePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        invalidateSessions();
    }

    public void updateAdministration(Status status, Role role) {
        if (status != null) {
            this.status = status;
        }
        if (role != null) {
            this.role = role;
        }
        this.updatedAt = Instant.now();
    }

    public void invalidateSessions() {
        this.sessionVersion++;
        this.updatedAt = Instant.now();
    }

    public enum Role {
        USER,
        ADMIN
    }

    public enum Status {
        ACTIVE,
        DISABLED
    }
}
