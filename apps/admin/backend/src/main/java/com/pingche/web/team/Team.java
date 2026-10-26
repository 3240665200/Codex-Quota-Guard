package com.pingche.web.team;

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
@org.hibernate.annotations.DynamicUpdate
@Table(name = "teams")
public class Team {
    @Column(name = "five_hour_usd_limit", precision = 30, scale = 12)
    private java.math.BigDecimal fiveHourUsdLimit;
    @Column(name = "weekly_usd_limit", precision = 30, scale = 12)
    private java.math.BigDecimal weeklyUsdLimit;
    @Column(name = "total_usd_limit", precision = 30, scale = 12)
    private java.math.BigDecimal totalUsdLimit;
    public java.math.BigDecimal getFiveHourUsdLimit() { return fiveHourUsdLimit; }
    public java.math.BigDecimal getWeeklyUsdLimit() { return weeklyUsdLimit; }
    public java.math.BigDecimal getTotalUsdLimit() { return totalUsdLimit; }

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    @Column(nullable = false, length = 1000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "five_hour_quota_percent", nullable = false)
    private int fiveHourQuotaPercent;

    @Column(name = "weekly_quota_percent", nullable = false)
    private int weeklyQuotaPercent;

    @Column(name = "five_hour_token_limit")
    private Long fiveHourTokenLimit;

    @Column(name = "weekly_token_limit")
    private Long weeklyTokenLimit;

    @Column(name = "total_token_limit")
    private Long totalTokenLimit;

    @Column(name = "official_account_id")
    private UUID officialAccountId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "quota_reset_at")
    private Instant quotaResetAt;

    protected Team() {
    }

    public Team(
            String name,
            String description,
            Status status,
            int fiveHourQuotaPercent,
            int weeklyQuotaPercent,
            Instant now
    ) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.description = description;
        this.status = status;
        this.fiveHourQuotaPercent = fiveHourQuotaPercent;
        this.weeklyQuotaPercent = weeklyQuotaPercent;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Status getStatus() {
        return status;
    }

    public int getFiveHourQuotaPercent() {
        return fiveHourQuotaPercent;
    }

    public int getWeeklyQuotaPercent() {
        return weeklyQuotaPercent;
    }

    public Long getFiveHourTokenLimit() {
        return fiveHourTokenLimit;
    }

    public Long getWeeklyTokenLimit() {
        return weeklyTokenLimit;
    }

    public Long getTotalTokenLimit() {
        return totalTokenLimit;
    }

    public UUID getOfficialAccountId() {
        return officialAccountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getQuotaResetAt() {
        return quotaResetAt;
    }

    public void resetQuota(Instant now) {
        this.quotaResetAt = now;
        this.updatedAt = now;
    }

    public void update(
            String name,
            String description,
            Status status,
            int fiveHourQuotaPercent,
            int weeklyQuotaPercent,
            Instant now
    ) {
        this.name = name;
        this.description = description;
        this.status = status;
        this.fiveHourQuotaPercent = fiveHourQuotaPercent;
        this.weeklyQuotaPercent = weeklyQuotaPercent;
        this.updatedAt = now;
    }

    public boolean updateTokenLimits(Long fiveHourTokenLimit, Long weeklyTokenLimit,
            Long totalTokenLimit, UUID officialAccountId, Instant now) {
        if (java.util.Objects.equals(this.fiveHourTokenLimit, fiveHourTokenLimit)
                && java.util.Objects.equals(this.weeklyTokenLimit, weeklyTokenLimit)
                && java.util.Objects.equals(this.totalTokenLimit, totalTokenLimit)
                && java.util.Objects.equals(this.officialAccountId, officialAccountId)) {
            return false;
        }
        this.fiveHourTokenLimit = fiveHourTokenLimit;
        this.weeklyTokenLimit = weeklyTokenLimit;
        this.totalTokenLimit = totalTokenLimit;
        this.officialAccountId = officialAccountId;
        this.updatedAt = now;
        return true;
    }

    public enum Status {
        ACTIVE,
        DISABLED
    }
}
