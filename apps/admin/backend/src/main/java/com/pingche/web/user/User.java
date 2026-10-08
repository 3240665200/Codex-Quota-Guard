package com.pingche.web.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
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

    /**
     * Kept as an identifier to avoid eagerly loading team data in authentication
     * and ordinary user-profile paths.
     */
    @Column(name = "team_id")
    private UUID teamId;

    @Column(name = "five_hour_quota_percent")
    private Integer fiveHourQuotaPercent;

    @Column(name = "weekly_quota_percent")
    private Integer weeklyQuotaPercent;

    @Column(name = "five_hour_token_limit")
    private Long fiveHourTokenLimit;

    @Column(name = "weekly_token_limit")
    private Long weeklyTokenLimit;

    @Column(name = "quota_reset_at")
    private Instant quotaResetAt;

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

    public UUID getTeamId() {
        return teamId;
    }

    public Integer getFiveHourQuotaPercent() {
        return fiveHourQuotaPercent;
    }

    public Integer getWeeklyQuotaPercent() {
        return weeklyQuotaPercent;
    }

    public Long getFiveHourTokenLimit() {
        return fiveHourTokenLimit;
    }

    public Long getWeeklyTokenLimit() {
        return weeklyTokenLimit;
    }

    public Instant getQuotaResetAt() {
        return quotaResetAt;
    }

    public void resetQuota(Instant now) {
        this.quotaResetAt = now;
        this.updatedAt = now;
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

    public boolean updateQuotaPolicy(
            UUID teamId,
            Integer fiveHourQuotaPercent,
            Integer weeklyQuotaPercent,
            Instant now
    ) {
        if (Objects.equals(this.teamId, teamId)
                && Objects.equals(this.fiveHourQuotaPercent, fiveHourQuotaPercent)
                && Objects.equals(this.weeklyQuotaPercent, weeklyQuotaPercent)) {
            return false;
        }
        this.teamId = teamId;
        this.fiveHourQuotaPercent = fiveHourQuotaPercent;
        this.weeklyQuotaPercent = weeklyQuotaPercent;
        this.updatedAt = now;
        return true;
    }

    public boolean clearTeam(Instant now) {
        return updateQuotaPolicy(null, fiveHourQuotaPercent, weeklyQuotaPercent, now);
    }

    public boolean updateTokenLimits(Long fiveHourTokenLimit, Long weeklyTokenLimit, Instant now) {
        if (Objects.equals(this.fiveHourTokenLimit, fiveHourTokenLimit)
                && Objects.equals(this.weeklyTokenLimit, weeklyTokenLimit)) {
            return false;
        }
        this.fiveHourTokenLimit = fiveHourTokenLimit;
        this.weeklyTokenLimit = weeklyTokenLimit;
        this.updatedAt = now;
        return true;
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
